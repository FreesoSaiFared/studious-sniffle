use aa_protocol::{unix_millis, RequestRecord};
use aa_store::Store;
use anyhow::{anyhow, Context, Result};
use clap::Parser;
use std::cmp::min;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::Instant;

const MAX_HEADER: usize = 256 * 1024;
const MAX_CAPTURE: usize = 1024 * 1024;

#[derive(Debug, Parser)]
#[command(version, about = "Anything Analyzer native forward proxy")]
struct Args {
    #[arg(long, default_value = "anything-analyzer-native.db")]
    db: String,
    #[arg(long, default_value = "proxy-session")]
    session: String,
    #[arg(long, default_value = "127.0.0.1:8899")]
    listen: String,
    /// Exit after accepting this many connections; zero means run forever.
    #[arg(long, default_value_t = 0)]
    max_connections: usize,
}

fn main() -> Result<()> {
    let args = Args::parse();
    let store = Arc::new(Store::open(&args.db)?);
    store.migrate()?;
    store.create_session(
        &args.session,
        "Proxy session",
        &format!("proxy://{}", args.listen),
        unix_millis(),
    )?;
    let listener = TcpListener::bind(&args.listen)
        .with_context(|| format!("bind proxy listener {}", args.listen))?;
    eprintln!("aa_proxy listening on {}", args.listen);
    let mut accepted = 0_usize;
    let mut bounded_workers = Vec::new();
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let store = Arc::clone(&store);
                let session = args.session.clone();
                let worker = thread::spawn(move || {
                    if let Err(error) = handle_client(stream, &store, &session) {
                        eprintln!("proxy client error: {error:#}");
                    }
                });
                accepted += 1;
                if args.max_connections == 0 {
                    drop(worker);
                } else {
                    bounded_workers.push(worker);
                    if accepted >= args.max_connections {
                        break;
                    }
                }
            }
            Err(error) => eprintln!("accept error: {error}"),
        }
    }
    for worker in bounded_workers {
        let _ = worker.join();
    }
    Ok(())
}

fn handle_client(mut client: TcpStream, store: &Store, session_id: &str) -> Result<()> {
    let started = Instant::now();
    let raw = read_headers(&mut client)?;
    let header_end = raw
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|position| position + 4)
        .ok_or_else(|| anyhow!("incomplete request headers"))?;
    let headers = &raw[..header_end];
    let initial_body = &raw[header_end..];
    let headers_text = String::from_utf8_lossy(headers).into_owned();
    let request_line = headers_text
        .lines()
        .next()
        .ok_or_else(|| anyhow!("missing request line"))?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().ok_or_else(|| anyhow!("missing method"))?;
    let uri = parts.next().ok_or_else(|| anyhow!("missing URI"))?;
    let version = parts.next().unwrap_or("HTTP/1.1");

    let mut record = RequestRecord::new(session_id, method, uri);
    record.request_headers = headers_text.clone();
    record.request_body = (!initial_body.is_empty()).then(|| {
        String::from_utf8_lossy(&initial_body[..min(initial_body.len(), MAX_CAPTURE)]).into()
    });
    record.source = "proxy".into();

    if method.eq_ignore_ascii_case("CONNECT") {
        let (host, port) = split_authority(uri, 443)?;
        record.url = format!("https://{uri}");
        let mut remote = TcpStream::connect((host.as_str(), port))
            .with_context(|| format!("connect {host}:{port}"))?;
        let established =
            b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: aa-proxy/0.2\r\n\r\n";
        client.write_all(established)?;
        record.status_code = Some(200);
        record.response_headers = String::from_utf8_lossy(established).into_owned();
        tunnel(&mut client, &mut remote)?;
    } else {
        let host_header =
            header_value(&headers_text, "host").ok_or_else(|| anyhow!("missing Host header"))?;
        let target = parse_target(uri, &host_header)?;
        record.url = format!(
            "http://{}{}{}",
            target.host,
            if target.port == 80 {
                String::new()
            } else {
                format!(":{}", target.port)
            },
            target.path
        );
        let mut remote = TcpStream::connect((target.host.as_str(), target.port))
            .with_context(|| format!("connect {}:{}", target.host, target.port))?;
        let rewritten = rewrite_request(&headers_text, method, &target.path, version);
        remote.write_all(rewritten.as_bytes())?;
        remote.write_all(initial_body)?;
        remote.shutdown(Shutdown::Write)?;

        let mut captured = Vec::new();
        let mut buffer = [0_u8; 64 * 1024];
        loop {
            let count = remote.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            client.write_all(&buffer[..count])?;
            if captured.len() < MAX_CAPTURE {
                let keep = min(count, MAX_CAPTURE - captured.len());
                captured.extend_from_slice(&buffer[..keep]);
            }
        }
        parse_response(&captured, &mut record);
    }
    record.duration_ms = Some(started.elapsed().as_millis() as i64);
    store.add_request(record)?;
    Ok(())
}

fn read_headers(stream: &mut TcpStream) -> Result<Vec<u8>> {
    let mut data = Vec::new();
    let mut buffer = [0_u8; 8192];
    while !data.windows(4).any(|window| window == b"\r\n\r\n") {
        let count = stream.read(&mut buffer)?;
        if count == 0 {
            return Err(anyhow!("client closed before headers"));
        }
        data.extend_from_slice(&buffer[..count]);
        if data.len() > MAX_HEADER {
            return Err(anyhow!("request headers exceed {MAX_HEADER} bytes"));
        }
    }
    Ok(data)
}

#[derive(Debug)]
struct Target {
    host: String,
    port: u16,
    path: String,
}

fn parse_target(uri: &str, host_header: &str) -> Result<Target> {
    if let Some(rest) = uri.strip_prefix("http://") {
        let (authority, path) = rest
            .split_once('/')
            .map(|(authority, path)| (authority, format!("/{path}")))
            .unwrap_or((rest, "/".to_string()));
        let (host, port) = split_authority(authority, 80)?;
        Ok(Target { host, port, path })
    } else {
        let (host, port) = split_authority(host_header, 80)?;
        Ok(Target {
            host,
            port,
            path: uri.to_string(),
        })
    }
}

fn split_authority(authority: &str, default_port: u16) -> Result<(String, u16)> {
    if authority.starts_with('[') {
        let close = authority
            .find(']')
            .ok_or_else(|| anyhow!("invalid IPv6 authority"))?;
        let host = authority[1..close].to_string();
        let port = authority
            .get(close + 1..)
            .and_then(|suffix| suffix.strip_prefix(':'))
            .map(str::parse)
            .transpose()?
            .unwrap_or(default_port);
        return Ok((host, port));
    }
    match authority.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') => Ok((host.to_string(), port.parse()?)),
        _ => Ok((authority.to_string(), default_port)),
    }
}

fn header_value(headers: &str, name: &str) -> Option<String> {
    headers.lines().skip(1).find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case(name)
            .then(|| value.trim().trim_end_matches('\r').to_string())
    })
}

fn rewrite_request(headers: &str, method: &str, path: &str, version: &str) -> String {
    let mut output = format!("{method} {path} {version}\r\n");
    let mut has_connection = false;
    for line in headers.lines().skip(1) {
        let line = line.trim_end_matches('\r');
        if line.is_empty() {
            continue;
        }
        if line.to_ascii_lowercase().starts_with("proxy-connection:") {
            continue;
        }
        if line.to_ascii_lowercase().starts_with("connection:") {
            output.push_str("Connection: close\r\n");
            has_connection = true;
        } else {
            output.push_str(line);
            output.push_str("\r\n");
        }
    }
    if !has_connection {
        output.push_str("Connection: close\r\n");
    }
    output.push_str("\r\n");
    output
}

fn parse_response(captured: &[u8], record: &mut RequestRecord) {
    let marker = captured
        .windows(4)
        .position(|window| window == b"\r\n\r\n");
    if let Some(position) = marker {
        let end = position + 4;
        record.response_headers = String::from_utf8_lossy(&captured[..end]).into_owned();
        record.response_body = Some(String::from_utf8_lossy(&captured[end..]).into_owned());
        let status_line = record.response_headers.lines().next().unwrap_or_default();
        record.status_code = status_line
            .split_whitespace()
            .nth(1)
            .and_then(|value| value.parse().ok());
        record.content_type = header_value(&record.response_headers, "content-type");
        record.is_streaming = record
            .content_type
            .as_deref()
            .is_some_and(|content_type| content_type.contains("text/event-stream"));
        record.is_websocket = record.status_code == Some(101);
    } else if !captured.is_empty() {
        record.response_body = Some(String::from_utf8_lossy(captured).into_owned());
    }
}

fn tunnel(client: &mut TcpStream, remote: &mut TcpStream) -> Result<()> {
    let mut client_read = client.try_clone()?;
    let mut remote_write = remote.try_clone()?;
    let upstream = thread::spawn(move || std::io::copy(&mut client_read, &mut remote_write));
    std::io::copy(remote, client)?;
    let _ = upstream.join();
    Ok(())
}
