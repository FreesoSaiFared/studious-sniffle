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
    training_scale=np.asarray(pipe[0].scale_,float)
    raw_coefs=coefs/training_scale
    result.update({"status":"fit","class_balance":float(y.mean()),"cv_auc_mean":float(scores.mean()),"cv_auc_std":float(scores.std()),
                   "coefficients_standardized":dict(sorted(zip(base_features,map(float,coefs)),key=lambda kv:-abs(kv[1]))),
                   "training_feature_scale":dict(zip(base_features,map(float,training_scale))),
                   "coefficients_raw":dict(zip(base_features,map(float,raw_coefs)))})
    return result

pub=fit_model(Xpub,ypub,"public_reception_exploratory")
sel=fit_model(Xsel,ysel,"creator_selection_exploratory")
model_versions=sorted({str(p.get("model","")) for p in pairs if p.get("model")})
pub.update({"evidence_class":"same_input_public_sibling_observational","model_versions":model_versions})
sel.update({"evidence_class":"same_input_creator_selection_observational","model_versions":model_versions})
(out/"public_preference_model.json").write_text(json.dumps(pub,indent=2)+"\n")
(out/"creator_selection_model.json").write_text(json.dumps(sel,indent=2)+"\n")

good=[r for r in deltas if r.get("a_ok") and r.get("b_ok")]
if good:
    M=np.array([[r["d_"+k] for k in base_features] for r in good],float)
    pair_scale=M.std(axis=0); pair_scale[pair_scale<1e-12]=1
    med_abs=np.median(np.abs(M),axis=0)
    stochasticity=[{"feature":k,"median_abs_delta":float(v),"std_delta":float(s),
                    "median_abs_delta_in_pair_sd":float(v/s)}
                   for k,v,s in zip(base_features,med_abs,pair_scale)]
    stochasticity.sort(key=lambda r:-r["median_abs_delta_in_pair_sd"])
else:
    M=np.empty((0,len(base_features))); pair_scale=np.ones(len(base_features)); stochasticity=[]

for model in (pub,sel):
    if model.get("status")=="fit":
        raw=np.array([model["coefficients_raw"][k] for k in base_features],float)
        common=raw*pair_scale
        model["coefficients_pair_sd_common"]=dict(zip(base_features,map(float,common)))
        model["gradient_coordinate"]="common_pair_standard_deviation"
(out/"public_preference_model.json").write_text(json.dumps(pub,indent=2)+"\n")
(out/"creator_selection_model.json").write_text(json.dumps(sel,indent=2)+"\n")

(out/"same_input_stochasticity.json").write_text(json.dumps({
    "evidence_class":"identical_observable_input_sibling_acoustic_variance",
    "model_versions":model_versions,"n_pairs_both_features":len(good),"ranking":stochasticity
},indent=2)+"\n")

gradient_comparison={"evidence_class":"public_vs_creator_gradient_comparison","model_versions":model_versions,
                     "public_status":pub.get("status"),"creator_status":sel.get("status")}
if pub.get("status")=="fit" and sel.get("status")=="fit":
    pc=np.array([pub["coefficients_pair_sd_common"][k] for k in base_features],float)
    sc=np.array([sel["coefficients_pair_sd_common"][k] for k in base_features],float)
    gradient_comparison.update({
      "cosine_similarity":float(np.dot(pc,sc)/(np.linalg.norm(pc)*np.linalg.norm(sc)+1e-12)),
      "sign_agreement_fraction":float(np.mean(np.sign(pc)==np.sign(sc))),
      "feature_comparison":[{"feature":k,"beta_public":float(x),"beta_operator":float(y),
                             "same_sign":bool(np.sign(x)==np.sign(y))}
                            for k,x,y in zip(base_features,pc,sc)]
    })
(out/"preference_gradient_comparison.json").write_text(json.dumps(gradient_comparison,indent=2)+"\n")

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

# operation-vector alignment against public and creator gradients; exploratory only
alignment=[]
pub_coef=np.array([pub["coefficients_pair_sd_common"][k] for k in base_features],float) if pub.get("status")=="fit" else None
sel_coef=np.array([sel["coefficients_pair_sd_common"][k] for k in base_features],float) if sel.get("status")=="fit" else None
for r in oprows:
    v=np.array([r["mean_d_"+k] for k in base_features],float)/pair_scale
    item={"relation":r["relation"],"n":r["n"],"evidence_class":"parent_child_lineage_acoustic_operation_vector",
          "model_versions":model_versions}
    if pub_coef is not None: item["public_alignment_score_exploratory"]=float(np.dot(pub_coef,v))
    if sel_coef is not None: item["creator_alignment_score_exploratory"]=float(np.dot(sel_coef,v))
    alignment.append(item)
alignment.sort(key=lambda r:-abs(float(r.get("public_alignment_score_exploratory",0))))
(out/"operation_alignment.json").write_text(json.dumps(alignment,indent=2)+"\n")
with (out/"preference_vector_alignment.tsv").open("w",newline="",encoding="utf-8") as f:
    fields=["relation","n","evidence_class","model_versions","public_alignment_score_exploratory","creator_alignment_score_exploratory"]
    w=csv.DictWriter(f,fieldnames=fields,delimiter="\t",lineterminator="\n"); w.writeheader()
    for r in alignment:
        rr={**r,"model_versions":",".join(r.get("model_versions",[]))}
        w.writerow({k:rr.get(k,"") for k in fields})

# active listening: prioritize public/operator disagreement, then observed outcome conflict
queue=[]
for r in deltas:
    if not (r.get("a_ok") and r.get("b_ok")): continue
    ac,bc=int(r["a_children"]),int(r["b_children"]); sel_label=(1 if ac>0 and bc==0 else 0 if bc>0 and ac==0 else None)
    rate=float(r.get("smoothed_upvote_rate_delta",0))
    observed_conflict=sel_label is not None and ((rate>0) != bool(sel_label)) and abs(rate)>=.01
    vec=np.array([float(r["d_"+k]) for k in base_features],float)
    z=vec/pair_scale
    public_margin=float(np.dot(pub_coef,z)) if pub_coef is not None else 0.0
    creator_margin=float(np.dot(sel_coef,z)) if sel_coef is not None else 0.0
    gradient_disagreement=(pub_coef is not None and sel_coef is not None and public_margin*creator_margin<0)
    magnitude=float(np.linalg.norm(z))
    queue.append({"family_id":r["family_id"],"a_id":r["a_id"],"b_id":r["b_id"],"model":r.get("model",""),
                  "evidence_class":"same_input_blinded_listening_candidate","public_rate_delta":rate,
                  "creator_selected_side":"a" if sel_label==1 else "b" if sel_label==0 else "none",
                  "observed_outcome_conflict":observed_conflict,"gradient_direction_disagreement":gradient_disagreement,
                  "public_model_margin":public_margin,"creator_model_margin":creator_margin,
                  "feature_delta_magnitude_pair_sd":magnitude})
queue.sort(key=lambda r:(not r["gradient_direction_disagreement"],not r["observed_outcome_conflict"],-r["feature_delta_magnitude_pair_sd"]))
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
target_family_relations={
  "artist_clip+persona_root":{"artist_clip,persona_root"},
  "cover_clip":{"cover_clip"},
  "concat_history":{"concat_history"},
  "stem_from":{"stem_from"},
  "history":{"history"},
}
family_rows=[]
for fam,relation_names in target_family_relations.items():
    matched=[r for r in alignment if r["relation"] in relation_names]
    usable=sum(int(r["n"]) for r in matched)
    family_rows.append({"family":fam,"usable_archive_edges":usable,
                        "status":"archive_sufficient" if usable>=30 else "residual_underpowered"})
gradient_disagreements=sum(bool(r.get("gradient_direction_disagreement")) for r in queue)
policy={
  "schema":"boozle-next-experiment-policy/0.1",
  "evidence_class":"archive_derived_mir_policy",
  "model_versions":model_versions,
  "archive_resolvable":[
    {"question":"same_input_acoustic_stochasticity","status":"resolved" if len(good)>=200 else "needs_more_archive_recovery",
     "new_suno_credits":False,"evidence_pairs":len(good)},
    {"question":"public_vs_creator_gradient","status":"resolved" if pub.get("status")=="fit" and sel.get("status")=="fit" else "needs_more_archive_or_labels",
     "new_suno_credits":False,"public_n":pub.get("n",0),"creator_n":sel.get("n",0)},
    {"question":"operation_vectors","status":"partially_resolved","new_suno_credits":False,"families":family_rows},
    {"question":"public_operator_disagreement","status":"requires_blind_human_listening" if gradient_disagreements else "no_model_disagreement_detected",
     "new_suno_credits":False,"queue_count":gradient_disagreements}
  ],
  "generation_zero_credit_candidates":[
    {"family":r["family"],"experiment":"controlled_same-input parent/intervention A-B repeat",
     "reason":"archive operation vector remains underpowered after usable-audio filtering",
     "minimum_new_pairs":max(0,30-r["usable_archive_edges"])}
    for r in family_rows if r["status"]=="residual_underpowered"
  ],
  "credit_gate":"SPEND_ONLY_FOR_RESIDUAL_OPERATION_UNCERTAINTY_AFTER_ARCHIVE_AND_BLIND_LISTENING",
  "forbidden_shortcuts":["duration_as_quality","parent_child_playcount_as_causal_quality","creator_selection_as_public_preference"]
}
(out/"next_experiment_policy.json").write_text(json.dumps(policy,indent=2)+"\n")
(out/"result_sha256.txt").write_text("\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in sorted(out.iterdir()) if p.is_file())+"\n")
print(json.dumps(receipt,indent=2))
