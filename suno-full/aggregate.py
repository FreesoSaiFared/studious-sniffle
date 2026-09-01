from __future__ import annotations
import argparse,csv,hashlib,json,pathlib,collections,statistics

ap=argparse.ArgumentParser()
ap.add_argument("--selected",required=True)
ap.add_argument("--results",required=True)
ap.add_argument("--out",required=True)
a=ap.parse_args()
res=pathlib.Path(a.results); out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)
with open(a.selected,encoding="utf-8",newline="") as f:
    selected=list(csv.DictReader(f,delimiter="\t"))
selected_ids=sorted(r["id"] for r in selected)
expected_sha=hashlib.sha256(("\n".join(selected_ids)+"\n").encode()).hexdigest()

files=sorted(res.glob("shard-??.jsonl"))
rows=[]
for p in files:
    with p.open(encoding="utf-8") as f:
        for line in f:
            if line.strip(): rows.append(json.loads(line))
ids=[r["id"] for r in rows]
unique=set(ids)
got_sha=hashlib.sha256(("\n".join(sorted(unique))+"\n").encode()).hexdigest() if unique else ""

def audio_field(r,key,default=None):
    try:
        streams=(r.get("probe") or {}).get("streams") or []
        return streams[0].get(key,default) if streams else default
    except Exception: return default
def format_field(r,key,default=None):
    try: return ((r.get("probe") or {}).get("format") or {}).get(key,default)
    except Exception: return default

http=collections.Counter(str(r.get("http_code",0)) for r in rows)
codecs=collections.Counter(str(audio_field(r,"codec_name","")) for r in rows if r.get("probe_ok"))
sr=collections.Counter(str(audio_field(r,"sample_rate","")) for r in rows if r.get("probe_ok"))
channels=collections.Counter(str(audio_field(r,"channels","")) for r in rows if r.get("probe_ok"))
model=collections.Counter(str(r.get("major_model_version","")) for r in rows)
types=collections.Counter(str(r.get("metadata_type","")) for r in rows)
reasons=collections.Counter(str(r.get("tranche_reason","")) for r in rows)
durations=[]
for r in rows:
    try: durations.append(float(format_field(r,"duration")))
    except Exception: pass

summary={
 "result_shard_files":len(files),
 "selected_rows":len(selected),
 "result_rows":len(rows),
 "unique_result_ids":len(unique),
 "missing_ids":len(set(selected_ids)-unique),
 "unexpected_ids":len(unique-set(selected_ids)),
 "selection_id_sha256_expected":expected_sha,
 "selection_id_sha256_results":got_sha,
 "selection_match":len(rows)==20000 and len(unique)==20000 and got_sha==expected_sha,
 "download_ok":sum(bool(r.get("download_ok")) for r in rows),
 "probe_ok":sum(bool(r.get("probe_ok")) for r in rows),
 "decode_ok":sum(bool(r.get("decode_ok")) for r in rows),
 "download_fail":sum(not bool(r.get("download_ok")) for r in rows),
 "probe_fail":sum(bool(r.get("download_ok")) and not bool(r.get("probe_ok")) for r in rows),
 "decode_fail":sum(bool(r.get("download_ok")) and not bool(r.get("decode_ok")) for r in rows),
 "total_bytes":sum(int(r.get("bytes") or 0) for r in rows),
 "probed_audio_hours":sum(durations)/3600 if durations else 0,
 "median_duration_seconds":statistics.median(durations) if durations else None,
 "http_codes":dict(http),
 "codecs":dict(codecs),
 "sample_rates":dict(sr),
 "channels":dict(channels),
 "model_counts":dict(model),
 "type_counts":dict(types),
 "reason_counts":dict(reasons),
}
(out/"integrity_summary.json").write_text(json.dumps(summary,indent=2,sort_keys=True)+"\n")

with (out/"integrity_20000.jsonl").open("w",encoding="utf-8") as f:
    for r in sorted(rows,key=lambda x:x["id"]):
        f.write(json.dumps(r,separators=(",",":"),ensure_ascii=False)+"\n")

fail=[r for r in rows if not r.get("download_ok") or not r.get("probe_ok") or not r.get("decode_ok")]
with (out/"failures.tsv").open("w",newline="",encoding="utf-8") as f:
    w=csv.writer(f,delimiter="\t",lineterminator="\n")
    w.writerow(["id","http_code","download_ok","probe_ok","decode_ok","model","type","reason","last_download_error","probe_error","decode_error"])
    for r in sorted(fail,key=lambda x:x["id"]):
        attempts=r.get("download_attempts") or []
        w.writerow([r["id"],r.get("http_code"),r.get("download_ok"),r.get("probe_ok"),r.get("decode_ok"),
                    r.get("major_model_version",""),r.get("metadata_type",""),r.get("tranche_reason",""),
                    (attempts[-1].get("stderr","") if attempts else ""),r.get("probe_error",""),r.get("decode_error","")])

(out/"result-sha256.txt").write_text(
    hashlib.sha256((out/"integrity_20000.jsonl").read_bytes()).hexdigest()+"  integrity_20000.jsonl\n"+
    hashlib.sha256((out/"integrity_summary.json").read_bytes()).hexdigest()+"  integrity_summary.json\n")
print(json.dumps(summary,indent=2,sort_keys=True))
if not summary["selection_match"]:
    raise SystemExit("aggregate selection integrity failed")
