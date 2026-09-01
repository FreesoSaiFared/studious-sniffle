from __future__ import annotations
import argparse, csv, hashlib, json, os, pathlib, subprocess, tempfile, time

TRANSIENT_HTTP={408,425,429,500,502,503,504}
SLEEPS=[2,5,12]

def run(cmd,timeout=None):
    return subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=timeout)

def parse_headers(path):
    if not path.exists(): return {}
    blocks=[]; cur=[]
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("HTTP/"):
            if cur: blocks.append(cur)
            cur=[line]
        elif cur:
            cur.append(line)
    if cur: blocks.append(cur)
    if not blocks: return {}
    h={}
    for line in blocks[-1][1:]:
        if ":" in line:
            k,v=line.split(":",1); h[k.strip().lower()]=v.strip()
    return h

def download(url,dst,hdr):
    attempts=[]
    for i in range(4):
        for p in (dst,hdr):
            try: p.unlink()
            except FileNotFoundError: pass
        fmt="%{http_code}\t%{url_effective}\t%{content_type}\t%{size_download}\t%{time_total}"
        p=run(["curl","-L","--silent","--show-error","--connect-timeout","20","--max-time","300",
               "--fail-with-body","-D",str(hdr),"-o",str(dst),"-w",fmt,url],timeout=330)
        parts=p.stdout.strip().split("\t")
        code=int(parts[0]) if parts and parts[0].isdigit() else 0
        attempt={"n":i+1,"curl_rc":p.returncode,"http_code":code,
                 "effective_url":parts[1] if len(parts)>1 else "",
                 "content_type":parts[2] if len(parts)>2 else "",
                 "size_download":float(parts[3]) if len(parts)>3 and parts[3] else 0,
                 "time_total":float(parts[4]) if len(parts)>4 and parts[4] else None,
                 "stderr":p.stderr[-1500:]}
        attempts.append(attempt)
        if p.returncode==0 and dst.exists() and dst.stat().st_size>0:
            return True,attempts
        if code and code not in TRANSIENT_HTTP:
            break
        if i<len(SLEEPS): time.sleep(SLEEPS[i])
    return False,attempts

def sha256_file(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda:f.read(8*1024*1024),b""): h.update(b)
    return h.hexdigest()

def probe(path):
    p=run(["ffprobe","-v","error","-select_streams","a:0",
           "-show_entries","format=format_name,duration,size,bit_rate:stream=codec_name,sample_rate,channels,channel_layout,bit_rate",
           "-of","json",str(path)],timeout=90)
    if p.returncode!=0:
        return False,None,p.stderr[-3000:]
    try: return True,json.loads(p.stdout),p.stderr[-3000:]
    except Exception as e: return False,None,f"json parse: {e}; stderr={p.stderr[-2000:]}"

def decode(path):
    t=time.monotonic()
    p=run(["ffmpeg","-nostdin","-v","error","-xerror","-i",str(path),"-map","0:a:0","-f","null","-"],timeout=420)
    return p.returncode==0,p.returncode,time.monotonic()-t,p.stderr[-4000:]

ap=argparse.ArgumentParser()
ap.add_argument("--selected",required=True)
ap.add_argument("--shard",type=int,required=True)
ap.add_argument("--shards",type=int,default=20)
ap.add_argument("--out",required=True)
a=ap.parse_args()
out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)

with open(a.selected,encoding="utf-8",newline="") as f:
    rows=list(csv.DictReader(f,delimiter="\t"))
rows=rows[a.shard*1000:(a.shard+1)*1000]
if len(rows)!=1000: raise SystemExit(f"shard {a.shard} has {len(rows)} rows")
result_path=out/f"shard-{a.shard:02d}.jsonl"
summary={"shard":a.shard,"selected":len(rows),"download_ok":0,"probe_ok":0,"decode_ok":0,"bytes":0,
         "http_fail":0,"probe_fail":0,"decode_fail":0,"started_unix":time.time()}
with result_path.open("w",encoding="utf-8") as fo:
  with tempfile.TemporaryDirectory(prefix=f"suno-{a.shard:02d}-") as td:
    td=pathlib.Path(td)
    for idx,row in enumerate(rows):
        dst=td/(row["id"]+".mp3"); hdr=td/(row["id"]+".headers")
        rec={**row,"shard":a.shard,"shard_index":idx}
        ok,attempts=download(row["audio_url"],dst,hdr)
        rec["download_ok"]=ok; rec["download_attempts"]=attempts
        rec["http_code"]=attempts[-1]["http_code"] if attempts else 0
        rec["effective_url"]=attempts[-1]["effective_url"] if attempts else ""
        rec["content_type"]=attempts[-1]["content_type"] if attempts else ""
        headers=parse_headers(hdr)
        rec["etag"]=headers.get("etag",""); rec["last_modified"]=headers.get("last-modified","")
        rec["accept_ranges"]=headers.get("accept-ranges","")
        if ok:
            summary["download_ok"]+=1
            rec["bytes"]=dst.stat().st_size; summary["bytes"]+=rec["bytes"]
            rec["sha256"]=sha256_file(dst)
            pok,pdata,perr=probe(dst)
            rec["probe_ok"]=pok; rec["probe"]=pdata; rec["probe_error"]=perr
            if pok: summary["probe_ok"]+=1
            else: summary["probe_fail"]+=1
            dok,drc,dsec,derr=decode(dst)
            rec["decode_ok"]=dok; rec["decode_rc"]=drc; rec["decode_seconds"]=dsec; rec["decode_error"]=derr
            if dok: summary["decode_ok"]+=1
            else: summary["decode_fail"]+=1
        else:
            summary["http_fail"]+=1
            rec.update({"bytes":0,"sha256":"","probe_ok":False,"probe":None,"probe_error":"",
                        "decode_ok":False,"decode_rc":None,"decode_seconds":None,"decode_error":""})
        fo.write(json.dumps(rec,separators=(",",":"),ensure_ascii=False)+"\n")
        fo.flush()
        if (idx+1)%100==0:
            print(f"shard={a.shard:02d} done={idx+1}/1000 dl={summary['download_ok']} decode={summary['decode_ok']}",flush=True)

summary["finished_unix"]=time.time(); summary["wall_seconds"]=summary["finished_unix"]-summary["started_unix"]
(out/f"shard-{a.shard:02d}-summary.json").write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
