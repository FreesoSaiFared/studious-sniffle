from __future__ import annotations
import argparse,csv,json,math,pathlib,hashlib,statistics
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline
from sklearn.model_selection import StratifiedKFold,cross_val_score

ap=argparse.ArgumentParser()
ap.add_argument("--pairset",required=True)
ap.add_argument("--results",required=True)
ap.add_argument("--out",required=True)
a=ap.parse_args()
out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)
records={}
for p in sorted(pathlib.Path(a.results).glob("features-*.jsonl")):
    for line in p.read_text().splitlines():
        if not line.strip(): continue
        r=json.loads(line); records[r["id"]]=r

pairs=list(csv.DictReader(open(pathlib.Path(a.pairset)/"pairs.tsv",encoding="utf-8"),delimiter="\t"))
ops=list(csv.DictReader(open(pathlib.Path(a.pairset)/"operation_edges.tsv",encoding="utf-8"),delimiter="\t"))
manifest=json.load(open(pathlib.Path(a.pairset)/"manifest.json"))

base_features=["duration","sample_mean_rms_mean","sample_mean_rms_std","sample_mean_crest_mean",
"sample_mean_centroid_mean","sample_mean_bandwidth_mean","sample_mean_rolloff85_mean",
"sample_mean_flatness_mean","sample_mean_zcr_mean","sample_mean_flux_mean","sample_mean_tempo_proxy",
"sample_mean_chroma_entropy","sample_mean_key_conf","section_contrast_mean","section_contrast_max",
"energy_arc_middle_minus_ends","energy_end_minus_start"]

def feat(cid):
    r=records.get(cid)
    return (r or {}).get("features") if r and r.get("ok") else None

def smrate(u,p): return (float(u)+.5)/(float(p)+1.0)

deltas=[]; Xpub=[]; ypub=[]; Xsel=[]; ysel=[]
for p in pairs:
    fa,fb=feat(p["a_id"]),feat(p["b_id"])
    row={**p,"a_ok":fa is not None,"b_ok":fb is not None}
    if fa and fb:
        dv={k:float(fa.get(k,0))-float(fb.get(k,0)) for k in base_features}
        row.update({"d_"+k:v for k,v in dv.items()})
        ar=smrate(p["a_ups"],p["a_plays"]); br=smrate(p["b_ups"],p["b_plays"])
        row["smoothed_upvote_rate_delta"]=ar-br
        if min(int(p["a_plays"]),int(p["b_plays"]))>=10 and abs(ar-br)>=0.01:
            Xpub.append([dv[k] for k in base_features]); ypub.append(1 if ar>br else 0)
        ac,bc=int(p["a_children"]),int(p["b_children"])
        if (ac>0) != (bc>0):
            Xsel.append([dv[k] for k in base_features]); ysel.append(1 if ac>0 else 0)
    deltas.append(row)

with (out/"pair_deltas.tsv").open("w",newline="",encoding="utf-8") as f:
    fields=sorted({k for r in deltas for k in r.keys()})
    w=csv.DictWriter(f,fieldnames=fields,delimiter="\t",lineterminator="\n"); w.writeheader(); w.writerows(deltas)

def fit_model(X,y,name):
    result={"name":name,"n":len(y),"features":base_features}
    if len(y)<30 or len(set(y))<2:
        result["status"]="insufficient"; return result
    X=np.asarray(X,float); y=np.asarray(y,int)
    pipe=make_pipeline(StandardScaler(),LogisticRegression(C=.3,fit_intercept=False,max_iter=5000))
    folds=min(5,max(2,min(np.bincount(y))))
    cv=StratifiedKFold(n_splits=folds,shuffle=True,random_state=17)
    scores=cross_val_score(pipe,X,y,cv=cv,scoring="roc_auc")
    pipe.fit(X,y); lr=pipe[-1]
    coefs=lr.coef_[0]
    result.update({"status":"fit","class_balance":float(y.mean()),"cv_auc_mean":float(scores.mean()),"cv_auc_std":float(scores.std()),
                   "coefficients_standardized":dict(sorted(zip(base_features,map(float,coefs)),key=lambda kv:-abs(kv[1])))})
    return result

pub=fit_model(Xpub,ypub,"public_reception_exploratory")
sel=fit_model(Xsel,ysel,"creator_selection_exploratory")
(out/"public_preference_model.json").write_text(json.dumps(pub,indent=2)+"\n")
(out/"creator_selection_model.json").write_text(json.dumps(sel,indent=2)+"\n")

# operation vectors
oprows=[]
byrel={}
for e in ops:
    fc,fp=feat(e["child_id"]),feat(e["parent_id"])
    if not fc or not fp: continue
    d=np.array([float(fc.get(k,0))-float(fp.get(k,0)) for k in base_features])
    byrel.setdefault(e["relations"],[]).append(d)
for rel,arr in sorted(byrel.items()):
    A=np.vstack(arr); med=np.median(A,axis=0); mean=A.mean(axis=0)
    row={"relation":rel,"n":len(A)}
    for k,v in zip(base_features,med): row["median_d_"+k]=float(v)
    for k,v in zip(base_features,mean): row["mean_d_"+k]=float(v)
    oprows.append(row)
with (out/"operation_vectors.tsv").open("w",newline="",encoding="utf-8") as f:
    fields=sorted({k for r in oprows for k in r})
    w=csv.DictWriter(f,fieldnames=fields,delimiter="\t",lineterminator="\n"); w.writeheader(); w.writerows(oprows)

# alignment uses standardized public coefficients only as exploratory direction
alignment=[]
if pub.get("status")=="fit":
    coef=np.array([pub["coefficients_standardized"][k] for k in base_features])
    # standardize op mean only by signless feature scale estimated from pair deltas
    good=[r for r in deltas if r.get("a_ok") and r.get("b_ok")]
    M=np.array([[r["d_"+k] for k in base_features] for r in good],float)
    scale=M.std(axis=0); scale[scale<1e-12]=1
    for r in oprows:
        v=np.array([r["mean_d_"+k] for k in base_features])/scale
        alignment.append({"relation":r["relation"],"n":r["n"],"public_alignment_score_exploratory":float(np.dot(coef,v))})
alignment.sort(key=lambda r:-r["public_alignment_score_exploratory"])
(out/"operation_alignment.json").write_text(json.dumps(alignment,indent=2)+"\n")

# active listening: pairs with both audio but ambiguous public signal or public/creator conflict
queue=[]
for r in deltas:
    if not (r.get("a_ok") and r.get("b_ok")): continue
    ac,bc=int(r["a_children"]),int(r["b_children"]); sel_label=(1 if ac>0 and bc==0 else 0 if bc>0 and ac==0 else None)
    rate=float(r.get("smoothed_upvote_rate_delta",0))
    conflict=sel_label is not None and ((rate>0) != bool(sel_label)) and abs(rate)>=.01
    vec=np.array([float(r["d_"+k]) for k in base_features])
    magnitude=float(np.linalg.norm(vec/(np.std(vec)+1e-9)))
    queue.append({"family_id":r["family_id"],"a_id":r["a_id"],"b_id":r["b_id"],"public_rate_delta":rate,
                  "creator_selected_side":"a" if sel_label==1 else "b" if sel_label==0 else "none",
                  "objective_conflict":conflict,"feature_delta_magnitude_proxy":magnitude})
queue.sort(key=lambda r:(not r["objective_conflict"],-r["feature_delta_magnitude_proxy"]))
with (out/"active_listening_queue.tsv").open("w",newline="",encoding="utf-8") as f:
    fields=list(queue[0]) if queue else ["family_id"]
    w=csv.DictWriter(f,fieldnames=fields,delimiter="\t",lineterminator="\n"); w.writeheader(); w.writerows(queue[:250])

fail=[r for r in records.values() if not r.get("ok")]
receipt={"pair_manifest":manifest,"feature_records":len(records),"feature_ok":sum(bool(r.get("ok")) for r in records.values()),
         "feature_fail":len(fail),"pairs":len(pairs),"pairs_both_features":sum(feat(p["a_id"]) is not None and feat(p["b_id"]) is not None for p in pairs),
         "public_model_n":len(ypub),"selection_model_n":len(ysel),"operation_edges_with_features":sum(len(v) for v in byrel.values()),
         "failure_stages":{}}
for r in fail: receipt["failure_stages"][r.get("stage","unknown")]=receipt["failure_stages"].get(r.get("stage","unknown"),0)+1
(out/"receipt.json").write_text(json.dumps(receipt,indent=2)+"\n")
(out/"failures.jsonl").write_text("".join(json.dumps(r,separators=(",",":"))+"\n" for r in fail))
(out/"result_sha256.txt").write_text("\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in sorted(out.iterdir()) if p.is_file())+"\n")
print(json.dumps(receipt,indent=2))
