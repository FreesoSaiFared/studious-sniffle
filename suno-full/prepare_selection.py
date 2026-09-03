from __future__ import annotations
import argparse, csv, duckdb, hashlib, math, collections, pathlib, json

EXPECTED_ID_SHA256 = "0ee10311ee761aaba8f206d02eb36bddaeece6b0724ef59c57de85ef166546a7"
TARGET = 20000

def h64(s):
    return int.from_bytes(hashlib.sha256(str(s).encode()).digest()[:8], "big")

def clean(v, none="none"):
    return none if v is None or str(v) == "" else str(v)

def dbin(x):
    if x is None: return "missing"
    x=float(x)
    if x < 30: return "<30"
    if x < 60: return "30-60"
    if x < 120: return "60-120"
    if x < 240: return "120-240"
    if x < 360: return "240-360"
    return "360+"

ap=argparse.ArgumentParser()
ap.add_argument("--parquet", required=True)
ap.add_argument("--out", required=True)
a=ap.parse_args()
out=pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
con=duckdb.connect(":memory:")
con.execute("PRAGMA threads=4")
con.execute(f"""CREATE TABLE raw AS
SELECT row_number() over ()::BIGINT obs_row_id,* FROM read_parquet('{a.parquet}')""")
con.execute("""CREATE TABLE node_agg AS
SELECT id,count(*) observation_count,min(obs_row_id) canonical_obs_row_id,
 bool_or(coalesce(audio_url,'')<>'') has_audio_any,
 bool_or(coalesce(is_public,false)) public_any,
 bool_or(status='complete') complete_any,
 max(play_count) play_count_max,max(upvote_count) upvote_count_max
FROM raw GROUP BY id""")
con.execute("""CREATE TABLE nodes AS
SELECT a.*,r.audio_url,r.major_model_version,r.metadata_type,r.metadata_task,
 r.metadata_duration,try_cast(r.created_at as timestamp) created_ts
FROM node_agg a JOIN raw r ON r.obs_row_id=a.canonical_obs_row_id""")
con.execute("""CREATE TABLE lineage AS
WITH direct AS (
 SELECT id child_id,metadata_artist_clip_id parent_id,'artist_clip' relation FROM raw WHERE coalesce(metadata_artist_clip_id,'')<>'' UNION ALL
 SELECT id,metadata_cover_clip_id,'cover_clip' FROM raw WHERE coalesce(metadata_cover_clip_id,'')<>'' UNION ALL
 SELECT id,metadata_stem_from_id,'stem_from' FROM raw WHERE coalesce(metadata_stem_from_id,'')<>'' UNION ALL
 SELECT id,json_extract_string(persona,'$.root_clip_id'),'persona_root' FROM raw
  WHERE persona IS NOT NULL AND coalesce(json_extract_string(persona,'$.root_clip_id'),'')<>''
), hist AS (
 SELECT r.id,json_extract_string(j.value,'$.id'),'history' FROM raw r,json_each(try_cast(r.metadata_history AS JSON)) j
 WHERE r.metadata_history IS NOT NULL AND coalesce(json_extract_string(j.value,'$.id'),'')<>''
), concat AS (
 SELECT r.id,json_extract_string(j.value,'$.id'),'concat_history' FROM raw r,json_each(try_cast(r.metadata_concat_history AS JSON)) j
 WHERE r.metadata_concat_history IS NOT NULL AND coalesce(json_extract_string(j.value,'$.id'),'')<>''
), all_e AS (SELECT * FROM direct UNION ALL SELECT * FROM hist UNION ALL SELECT * FROM concat)
SELECT child_id,parent_id,string_agg(DISTINCT relation,',' ORDER BY relation) relations
FROM all_e
WHERE child_id<>'' AND parent_id<>'' AND child_id<>parent_id
GROUP BY child_id,parent_id""")
con.execute("""CREATE TABLE graph AS
SELECT l.child_id,l.parent_id,l.relations
FROM lineage l JOIN nodes p ON p.id=l.parent_id""")
con.execute("""CREATE TABLE degree AS
SELECT id,sum(pc)::BIGINT parent_count,sum(cc)::BIGINT child_count FROM (
 SELECT n.id,count(g.parent_id) pc,0 cc FROM nodes n LEFT JOIN graph g ON g.child_id=n.id GROUP BY n.id
 UNION ALL
 SELECT n.id,0 pc,count(g.child_id) cc FROM nodes n LEFT JOIN graph g ON g.parent_id=n.id GROUP BY n.id
) q GROUP BY id""")

eligible="n.complete_any AND n.public_any AND n.has_audio_any AND coalesce(n.audio_url,'')<>''"
rows=con.execute(f"""SELECT n.id,n.audio_url,n.major_model_version,n.metadata_type,n.metadata_task,
 n.metadata_duration,n.created_ts,d.parent_count,d.child_count
FROM nodes n JOIN degree d using(id) WHERE {eligible}""").fetchall()

core=[r for r in rows if r[7]+r[8]>0]
core.sort(key=lambda r:r[0])
remaining=TARGET-len(core)
pool=[r for r in rows if r[7]+r[8]==0]

def is_force(r):
    model=clean(r[2],""); typ=clean(r[3],""); task=clean(r[4],"")
    return model in {"v2","v4"} or typ in {"upload","stem","upsample"} or task=="infill"

force=[r for r in pool if is_force(r)]
force.sort(key=lambda r:(h64(r[0]),r[0]))
force=force[:remaining]
force_ids={r[0] for r in force}
remaining2=remaining-len(force)
rest=[r for r in pool if r[0] not in force_ids]

strata=collections.defaultdict(list)
for r in rest:
    month=r[6].strftime("%Y-%m") if r[6] is not None else "unknown"
    key="|".join([clean(r[2],"unknown"),clean(r[3],"unknown"),clean(r[4],"none"),month,dbin(r[5])])
    strata[key].append(r)
counts={k:len(v) for k,v in sorted(strata.items())}
S=len(counts)
if remaining2 < S:
    keys=[k for k,v in sorted(counts.items(),key=lambda kv:(-kv[1],kv[0]))[:remaining2]]
    quotas={k:1 for k in keys}
else:
    quotas={k:1 for k in counts}
    capacity=remaining2-S
    weights={k:math.sqrt(v) for k,v in counts.items()}
    sw=sum(weights.values())
    ideal={k:capacity*weights[k]/sw for k in counts}
    for k in counts:
        quotas[k]+=min(math.floor(ideal[k]),counts[k]-1)
    left=remaining2-sum(quotas.values())
    frac=sorted(counts,key=lambda k:(-(ideal[k]-math.floor(ideal[k])),k))
    while left>0:
        progressed=False
        for k in frac:
            if left<=0: break
            if quotas[k] < counts[k]:
                quotas[k]+=1; left-=1; progressed=True
        if not progressed: break

baseline=[]; extra_pool=[]
for k in sorted(strata):
    group=sorted(strata[k],key=lambda r:(h64(r[0]),r[0]))
    q=quotas.get(k,0)
    baseline.extend(group[:q]); extra_pool.extend(group[q:])
if len(baseline)>remaining2:
    baseline=sorted(baseline,key=lambda r:(h64(r[0]),r[0]))[:remaining2]
elif len(baseline)<remaining2:
    baseline.extend(sorted(extra_pool,key=lambda r:(h64(r[0]),r[0]))[:remaining2-len(baseline)])

reason={}
for r in core: reason[r[0]]="lineage_core"
for r in force: reason[r[0]]="rare_model_or_operation"
for r in baseline: reason[r[0]]="stratified_baseline"
selected=core+force+baseline
assert len(selected)==TARGET and len({r[0] for r in selected})==TARGET

id_lines=sorted(r[0] for r in selected)
id_sha=hashlib.sha256(("\n".join(id_lines)+"\n").encode()).hexdigest()
if id_sha != EXPECTED_ID_SHA256:
    raise SystemExit(f"selection drift: {id_sha} != {EXPECTED_ID_SHA256}")

selected_ids=set(id_lines)
with (out/"selected.tsv").open("w",newline="",encoding="utf-8") as f:
    w=csv.writer(f,delimiter="\t",lineterminator="\n")
    w.writerow(["id","audio_url","major_model_version","metadata_type","metadata_task","tranche_reason"])
    for r in sorted(selected,key=lambda x:x[0]):
        w.writerow([r[0],r[1],clean(r[2],""),clean(r[3],""),clean(r[4],""),reason[r[0]]])

edges=con.execute("""SELECT child_id,parent_id,relations FROM graph ORDER BY child_id,parent_id""").fetchall()
inside=[e for e in edges if e[0] in selected_ids and e[1] in selected_ids]
with (out/"edges.tsv").open("w",newline="",encoding="utf-8") as f:
    w=csv.writer(f,delimiter="\t",lineterminator="\n")
    w.writerow(["child_id","parent_id","relations"]); w.writerows(inside)

manifest={
 "target":TARGET,
 "selected_id_sha256":id_sha,
 "eligible":len(rows),
 "lineage_core":len(core),
 "rare_model_or_operation":len(force),
 "stratified_baseline":len(baseline),
 "resolved_edges_inside":len(inside),
 "source_rows":con.execute("select count(*) from raw").fetchone()[0],
 "unique_clip_ids":con.execute("select count(*) from nodes").fetchone()[0],
}
(out/"selection-manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
print(json.dumps(manifest,indent=2))
