from __future__ import annotations
import argparse, base64, csv, hashlib, json, pathlib, subprocess, tempfile, time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

TRANSIENT_HTTP={408,425,429,500,502,503,504}
SLEEPS=[2,5,12]
CLOUDFRONT="https://d2lwuy8qc234o3.cloudfront.net/1/clip/{id}.m4a"
CLIP_API="https://studio-api.prod.suno.com/api/clip/{id}"
RIGHTS_API="https://studio-api.prod.suno.com/api/mango/rights"

def run(cmd,timeout=None):
    return subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=timeout)

def parse_headers(path):
    if not path.exists(): return {}
    blocks=[]; cur=[]
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("HTTP/"):
            if cur: blocks.append(cur)
            cur=[line]
        elif cur: cur.append(line)
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
                 "stderr":p.stderr[-1200:]}
        attempts.append(attempt)
        if p.returncode==0 and dst.exists() and dst.stat().st_size>0: return True,attempts
        if code and code not in TRANSIENT_HTTP: break
        if i<len(SLEEPS): time.sleep(SLEEPS[i])
    return False,attempts

def resolve_clip(clip_id):
    url=CLIP_API.format(id=clip_id); attempts=[]
    for i in range(4):
        p=run(["curl","-L","--silent","--show-error","--connect-timeout","15","--max-time","60",
               "-w","\n__HTTP__%{http_code}","-H","Accept: application/json",url],timeout=75)
        body,sep,tail=p.stdout.rpartition("\n__HTTP__")
        code=int(tail) if sep and tail.isdigit() else 0
        attempts.append({"n":i+1,"curl_rc":p.returncode,"http_code":code,"stderr":p.stderr[-1000:]})
        if p.returncode==0 and code==200:
            try:
                j=json.loads(body); media=[]
                for m in j.get("media_urls") or []:
                    u=m.get("url")
                    if u: media.append({"url":u,"content_type":m.get("content_type"),"delivery":m.get("delivery"),"encoding":m.get("encoding")})
                return {"ok":True,"http_code":code,"attempts":attempts,"is_public":j.get("is_public"),
                        "status":j.get("status"),"major_model_version":j.get("major_model_version"),
                        "media_urls":media,"audio_url":j.get("audio_url") or ""}
            except Exception as e:
                attempts[-1]["json_error"]=repr(e); break
        if code and code not in TRANSIENT_HTTP: break
        if i<len(SLEEPS): time.sleep(SLEEPS[i])
    return {"ok":False,"http_code":attempts[-1]["http_code"] if attempts else 0,"attempts":attempts,
            "is_public":None,"status":None,"major_model_version":None,"media_urls":[],"audio_url":""}

def acquire(row,dst,hdr):
    candidates=[("cloudfront_m4a_direct",CLOUDFRONT.format(id=row["id"]))]
    tried=[]; resolver=None
    def try_candidates(items):
        for label,url in items:
            if not url or any(x["url"]==url for x in tried): continue
            ok,attempts=download(url,dst,hdr)
            tried.append({"label":label,"url":url,"attempts":attempts})
            if ok: return True,label,url,attempts
        return None
    hit=try_candidates(candidates)
    if hit: return (*hit,tried,resolver)
    resolver=resolve_clip(row["id"])
    dynamic=[]
    for m in sorted(resolver.get("media_urls") or [],key=lambda m:(0 if m.get("content_type")=="m4a-opus" else 1,str(m.get("content_type")))):
        dynamic.append(("clip_api_"+str(m.get("content_type") or "media"),m.get("url")))
    au=resolver.get("audio_url") or ""
    if au and not au.endswith("/api/forbidden"): dynamic.append(("clip_api_audio_url",au))
    dynamic.append(("archived_audio_url",row["audio_url"]))
    hit=try_candidates(dynamic)
    if hit: return (*hit,tried,resolver)
    last=(tried[-1]["attempts"] if tried else [])
    return False,"","",last,tried,resolver

def _unwrap_suno_key(wrapped,song_id,user_key):
    payload=base64.b64decode(wrapped,validate=True)
    if len(payload)<=28: raise ValueError("wrapped rights payload too short")
    return AESGCM(user_key).decrypt(payload[:12],payload[12:],song_id.encode("utf-8"))

def fetch_rights(song_id):
    payload=json.dumps({"content_params":{"content_id":song_id,"content_type":"clip"}},separators=(",",":"))
    attempts=[]
    for i in range(4):
        p=run(["curl","-L","--silent","--show-error","--connect-timeout","15","--max-time","60",
               "-X","POST","-H","Content-Type: application/json","-H","Accept: application/json",
               "--data-binary",payload,"-w","\n__HTTP__%{http_code}",RIGHTS_API],timeout=75)
        body,sep,tail=p.stdout.rpartition("\n__HTTP__")
        code=int(tail) if sep and tail.isdigit() else 0
        a={"n":i+1,"curl_rc":p.returncode,"http_code":code,"stderr":p.stderr[-1000:]}
        attempts.append(a)
        if p.returncode==0 and code==200:
            try:
                j=json.loads(body)
                glt=str(j["glt"]); user_key=hashlib.sha256(glt.encode("utf-8")).digest()
                content_key=_unwrap_suno_key(str(j["key"]),song_id,user_key)
                content_iv=_unwrap_suno_key(str(j["iv"]),song_id,user_key)
                evidence={"ok":True,"http_code":code,"attempts":attempts,
                          "response_sha256":hashlib.sha256(body.encode()).hexdigest(),
                          "fields":sorted(j.keys()),"key_bytes":len(content_key),"iv_bytes":len(content_iv)}
                return evidence,content_key,content_iv
            except Exception as e:
                a["parse_or_unwrap_error"]=repr(e); break
        if code and code not in TRANSIENT_HTTP: break
        if i<len(SLEEPS): time.sleep(SLEEPS[i])
    return {"ok":False,"http_code":attempts[-1]["http_code"] if attempts else 0,"attempts":attempts},None,None

def decrypt_suno_m4a(src,dst,song_id):
    rights,key,iv=fetch_rights(song_id)
    if not rights.get("ok"): return False,rights,"rights failed"
    try:
        dec=Cipher(algorithms.AES(key),modes.CTR(iv)).decryptor()
        with src.open("rb") as fi,dst.open("wb") as fo:
            while True:
                b=fi.read(8*1024*1024)
                if not b: break
                fo.write(dec.update(b))
            fo.write(dec.finalize())
        with dst.open("rb") as f: head=f.read(12)
        if len(head)<12 or head[4:8]!=b"ftyp":
            return False,rights,"decrypted bytes lack MP4/M4A ftyp header"
        return True,rights,""
    except Exception as e:
        return False,rights,repr(e)

def sha256_file(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda:f.read(8*1024*1024),b""): h.update(b)
    return h.hexdigest()

def probe(path):
    p=run(["ffprobe","-v","error","-select_streams","a:0",
           "-show_entries","format=format_name,duration,size,bit_rate:stream=codec_name,sample_rate,channels,channel_layout,bit_rate",
           "-of","json",str(path)],timeout=90)
    if p.returncode!=0: return False,None,p.stderr[-3000:]
    try:
        data=json.loads(p.stdout)
        streams=data.get("streams") or []
        if not streams: return False,data,"ffprobe returned no audio stream"
        s=streams[0]
        if not s.get("codec_name"): return False,data,"audio stream lacks codec_name"
        return True,data,p.stderr[-3000:]
    except Exception as e:
        return False,None,f"json parse: {e}; stderr={p.stderr[-2000:]}"

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
with open(a.selected,encoding="utf-8",newline="") as f: rows=list(csv.DictReader(f,delimiter="\t"))
rows=rows[a.shard*1000:(a.shard+1)*1000]
if len(rows)!=1000: raise SystemExit(f"shard {a.shard} has {len(rows)} rows")
result_path=out/f"shard-{a.shard:02d}.jsonl"
summary={"shard":a.shard,"selected":len(rows),"download_ok":0,"decrypt_ok":0,"probe_ok":0,"decode_ok":0,
         "transport_bytes":0,"bytes":0,"http_fail":0,"decrypt_fail":0,"probe_fail":0,"decode_fail":0,
         "direct_cloudfront_ok":0,"resolver_used":0,"started_unix":time.time()}
with result_path.open("w",encoding="utf-8") as fo:
  with tempfile.TemporaryDirectory(prefix=f"suno-{a.shard:02d}-") as td:
    td=pathlib.Path(td)
    for idx,row in enumerate(rows):
        enc=td/(row["id"]+".transport"); audio=td/(row["id"]+".m4a"); hdr=td/(row["id"]+".headers")
        rec={**row,"shard":a.shard,"shard_index":idx}
        ok,label,url,attempts,tried,resolver=acquire(row,enc,hdr)
        rec["download_ok"]=ok; rec["acquisition_label"]=label; rec["acquisition_url"]=url
        rec["acquisition_attempts"]=tried; rec["resolver"]=resolver
        rec["http_code"]=attempts[-1]["http_code"] if attempts else 0
        rec["effective_url"]=attempts[-1].get("effective_url","") if attempts else ""
        rec["content_type"]=attempts[-1].get("content_type","") if attempts else ""
        headers=parse_headers(hdr)
        rec["etag"]=headers.get("etag",""); rec["last_modified"]=headers.get("last-modified","")
        rec["accept_ranges"]=headers.get("accept-ranges","")
        if label=="cloudfront_m4a_direct" and ok: summary["direct_cloudfront_ok"]+=1
        if resolver is not None: summary["resolver_used"]+=1
        if ok:
            summary["download_ok"]+=1
            rec["transport_bytes"]=enc.stat().st_size; summary["transport_bytes"]+=rec["transport_bytes"]
            rec["transport_sha256"]=sha256_file(enc)
            is_cloudfront=("cloudfront.net" in url and url.endswith(".m4a"))
            if is_cloudfront:
                dok,rights,derr=decrypt_suno_m4a(enc,audio,row["id"])
                rec["rights"]=rights; rec["decrypt_ok"]=dok; rec["decrypt_error"]=derr
            else:
                audio.write_bytes(enc.read_bytes()); dok=True
                rec["rights"]=None; rec["decrypt_ok"]=True; rec["decrypt_error"]=""
            if dok:
                summary["decrypt_ok"]+=1
                rec["bytes"]=audio.stat().st_size; summary["bytes"]+=rec["bytes"]
                rec["sha256"]=sha256_file(audio)
                pok,pdata,perr=probe(audio); rec["probe_ok"]=pok; rec["probe"]=pdata; rec["probe_error"]=perr
                if pok: summary["probe_ok"]+=1
                else: summary["probe_fail"]+=1
                fullok,drc,dsec,fullerr=decode(audio)
                rec["decode_ok"]=fullok; rec["decode_rc"]=drc; rec["decode_seconds"]=dsec; rec["decode_error"]=fullerr
                if fullok: summary["decode_ok"]+=1
                else: summary["decode_fail"]+=1
            else:
                summary["decrypt_fail"]+=1
                rec.update({"bytes":0,"sha256":"","probe_ok":False,"probe":None,"probe_error":"",
                            "decode_ok":False,"decode_rc":None,"decode_seconds":None,"decode_error":""})
        else:
            summary["http_fail"]+=1
            rec.update({"transport_bytes":0,"transport_sha256":"","decrypt_ok":False,"decrypt_error":"",
                        "rights":None,"bytes":0,"sha256":"","probe_ok":False,"probe":None,"probe_error":"",
                        "decode_ok":False,"decode_rc":None,"decode_seconds":None,"decode_error":""})
        fo.write(json.dumps(rec,separators=(",",":"),ensure_ascii=False)+"\n"); fo.flush()
        if (idx+1)%100==0:
            print(f"shard={a.shard:02d} done={idx+1}/1000 dl={summary['download_ok']} dec={summary['decrypt_ok']} probe={summary['probe_ok']} full={summary['decode_ok']}",flush=True)
summary["finished_unix"]=time.time(); summary["wall_seconds"]=summary["finished_unix"]-summary["started_unix"]
(out/f"shard-{a.shard:02d}-summary.json").write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
