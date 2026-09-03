from __future__ import annotations
import argparse,csv,duckdb,hashlib,json,pathlib,collections

ap=argparse.ArgumentParser()
ap.add_argument("--parquet",required=True)
ap.add_argument("--selected",required=True)
ap.add_argument("--edges",required=True)
ap.add_argument("--out",required=True)
a=ap.parse_args()
out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)
con=duckdb.connect(":memory:"); con.execute("PRAGMA threads=4")
con.execute(f"CREATE VIEW raw AS SELECT row_number() over ()::BIGINT obs_row_id,* FROM read_parquet('{a.parquet}')")
con.execute(f"CREATE TABLE selected AS SELECT * FROM read_csv_auto('{a.selected}',delim='\\t',header=true,all_varchar=true)")
con.execute("""CREATE TABLE agg AS
SELECT id,min(obs_row_id) canonical_obs_row_id,max(play_count) plays,max(upvote_count) ups
FROM raw WHERE id IN (SELECT id FROM selected) GROUP BY id""")
con.execute("""CREATE TABLE clips AS
SELECT a.id,a.plays,a.ups,r.user_id,r.handle,r.major_model_version,
 try_cast(r.created_at AS timestamp) created_ts,r.metadata_prompt,r.metadata_tags,
 r.metadata_gpt_description_prompt gpt_desc,r.metadata_negative_tags,r.metadata_type,r.metadata_task
FROM agg a JOIN raw r ON r.obs_row_id=a.canonical_obs_row_id""")
# only generation siblings; all members share exact observable input
cur=con.execute("""SELECT id,plays,ups,user_id,handle,major_model_version,created_ts,metadata_prompt,metadata_tags,gpt_desc,metadata_negative_tags,metadata_type,metadata_task FROM clips WHERE metadata_type='gen' ORDER BY id""")
names=[d[0] for d in cur.description]
rows=[dict(zip(names,r)) for r in cur.fetchall()]
groups=collections.defaultdict(list)
cols=["user_id","major_model_version","created_ts","metadata_prompt","metadata_tags","gpt_desc","metadata_negative_tags","metadata_task"]
for rec in rows:
    key=tuple("" if rec.get(col) is None else str(rec.get(col)) for col in cols)
    groups[key].append(rec)
# child counts from already-frozen selected lineage edges
child=collections.Counter()
with open(a.edges,encoding="utf-8",newline="") as f:
    for e in csv.DictReader(f,delimiter="\t"): child[e["parent_id"]]+=1
pairs=[]
for key,members in groups.items():
    if len(members)<2: continue
    members=sorted(members,key=lambda r:r["id"])
    for i in range(len(members)):
      for j in range(i+1,len(members)):
        x,y=members[i],members[j]
        raw="\x1f".join(key+(x["id"],y["id"]))
        pairs.append({
          "family_id":hashlib.sha256(raw.encode()).hexdigest()[:32],
          "a_id":x["id"],"b_id":y["id"],"model":x["major_model_version"] or "",
          "a_plays":int(x["plays"] or 0),"b_plays":int(y["plays"] or 0),
          "a_ups":int(x["ups"] or 0),"b_ups":int(y["ups"] or 0),
          "a_children":child[x["id"]],"b_children":child[y["id"]],
          "prompt_chars":len(x["metadata_prompt"] or ""),"tags_chars":len(x["metadata_tags"] or "")
        })
pairs.sort(key=lambda r:(r["family_id"],r["a_id"],r["b_id"]))
with (out/"pairs.tsv").open("w",newline="",encoding="utf-8") as f:
    w=csv.DictWriter(f,fieldnames=list(pairs[0]),delimiter="\t",lineterminator="\n")
    w.writeheader(); w.writerows(pairs)
# deterministic operation-edge sample: max 100 per relation string
byrel=collections.defaultdict(list)
with open(a.edges,encoding="utf-8",newline="") as f:
    for e in csv.DictReader(f,delimiter="\t"):
        score=hashlib.sha256((e["relations"]+"|"+e["child_id"]+"|"+e["parent_id"]).encode()).hexdigest()
        byrel[e["relations"]].append((score,e))
op=[]
for rel,v in sorted(byrel.items()):
    for _,e in sorted(v)[:100]:
        op.append(e)
with (out/"operation_edges.tsv").open("w",newline="",encoding="utf-8") as f:
    w=csv.DictWriter(f,fieldnames=["child_id","parent_id","relations"],delimiter="\t",lineterminator="\n")
    w.writeheader(); w.writerows(op)
target=sorted({p[k] for p in pairs for k in ("a_id","b_id")} | {e[k] for e in op for k in ("child_id","parent_id")})
(out/"targets.txt").write_text("\n".join(target)+"\n")
pair_hash=hashlib.sha256(("\n".join(f'{p["family_id"]}\t{p["a_id"]}\t{p["b_id"]}' for p in pairs)+"\n").encode()).hexdigest()
target_hash=hashlib.sha256(("\n".join(target)+"\n").encode()).hexdigest()
manifest={"pair_count":len(pairs),"target_count":len(target),"operation_edge_count":len(op),
          "pair_sha256":pair_hash,"target_sha256":target_hash,
          "relation_sample_counts":{k:min(100,len(v)) for k,v in byrel.items()}}
(out/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
print(json.dumps(manifest,indent=2))
