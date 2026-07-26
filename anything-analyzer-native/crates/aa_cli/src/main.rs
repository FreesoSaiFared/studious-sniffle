use aa_protocol::{unix_millis, RequestRecord};
use aa_store::Store;
use anyhow::Result;
use clap::{Parser, Subcommand};

#[derive(Debug, Parser)]
#[command(version, about = "Anything Analyzer native database CLI")]
struct Args {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    Init {
        #[arg(long)]
        db: String,
    },
    CreateSession {
        #[arg(long)]
        db: String,
        #[arg(long)]
        id: String,
        #[arg(long)]
        name: String,
        #[arg(long)]
        target_url: String,
    },
    StopSession {
        #[arg(long)]
        db: String,
        #[arg(long)]
        id: String,
    },
    AddRequest {
        #[arg(long)]
        db: String,
        #[arg(long)]
        session: String,
        #[arg(long)]
        method: String,
        #[arg(long)]
        url: String,
        #[arg(long)]
        status: Option<i32>,
        #[arg(long, default_value = "manual")]
        source: String,
    },
    Summary {
        #[arg(long)]
        db: String,
        #[arg(long)]
        session: String,
    },
    Export {
        #[arg(long)]
        db: String,
        #[arg(long)]
        session: String,
        #[arg(long)]
        output: String,
    },
    SelfTest {
        #[arg(long, default_value = "aa-self-test.db")]
        db: String,
    },
}

fn main() -> Result<()> {
    match Args::parse().command {
        Command::Init { db } => {
            let store = open(&db)?;
            store.migrate()?;
            println!("initialized {db}");
        }
        Command::CreateSession {
            db,
            id,
            name,
            target_url,
        } => {
            let store = open(&db)?;
            store.create_session(&id, &name, &target_url, unix_millis())?;
            println!("created {id}");
        }
        Command::StopSession { db, id } => {
            open(&db)?.stop_session(&id, unix_millis())?;
            println!("stopped {id}");
        }
        Command::AddRequest {
            db,
            session,
            method,
            url,
            status,
            source,
        } => {
            let store = open(&db)?;
            let mut request = RequestRecord::new(session, method, url);
            request.status_code = status;
            request.source = source;
            println!("{}", serde_json::to_string_pretty(&store.add_request(request)?)?);
        }
        Command::Summary { db, session } => {
            println!("{}", serde_json::to_string_pretty(&open(&db)?.summary(&session)?)?);
        }
        Command::Export {
            db,
            session,
            output,
        } => {
            open(&db)?.export_session_json(&session, &output)?;
            println!("exported {session} to {output}");
        }
        Command::SelfTest { db } => self_test(&db)?,
    }
    Ok(())
}

fn open(db: &str) -> Result<Store> {
    let store = Store::open(db)?;
    store.migrate()?;
    Ok(store)
}

fn self_test(db: &str) -> Result<()> {
    let _ = std::fs::remove_file(db);
    let store = open(db)?;
    store.create_session("self-test", "Self test", "https://example.test", unix_millis())?;
    let mut request = RequestRecord::new("self-test", "GET", "https://example.test/health");
    request.status_code = Some(200);
    request.response_body = Some("ok".into());
    request.source = "self-test".into();
    store.add_request(request)?;
    let summary = store.summary("self-test")?;
    anyhow::ensure!(summary.request_count == 1, "self-test request count mismatch");
    store.export_session_json("self-test", format!("{db}.json"))?;
    println!("self-test PASS: {}", serde_json::to_string(&summary)?);
    Ok(())
}
