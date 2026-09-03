from __future__ import annotations
import argparse,base64,csv,hashlib,json,math,pathlib,subprocess,tempfile,time
import numpy as np
from scipy.fft import dct
from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CF="https://d2lwuy8qc234o3.cloudfront.net/1/clip/{id}.m4a"
RIGHTS="https://studio-api.prod.suno.com/api/mango/rights"
SR=22050; SEG=20.0

def sh(cmd,timeout=180,binary=False):
    return subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=not binary,timeout=timeout)

def download(url,path):
    p=sh(["curl","-L","--silent","--show-error","--fail-with-body","--connect-timeout","20","--max-time","300","-o",str(path),url],330)
    return p.returncode==0 and path.exists() and path.stat().st_size>0,p.stderr[-1200:]

def rights(song_id):
    payload=json.dumps({"content_params":{"content_id":song_id,"content_type":"clip"}},separators=(",",":"))
    p=sh(["curl","-L","--silent","--show-error","--fail-with-body","--connect-timeout","15","--max-time","60",
          "-X","POST","-H","Content-Type: application/json","--data-binary",payload,RIGHTS],75)
    if p.returncode: return None,{"ok":False,"error":p.stderr[-1200:]}
    try:
        j=json.loads(p.stdout); glt=str(j["glt"]); user=hashlib.sha256(glt.encode()).digest()
        def unwrap(v):
            b=base64.b64decode(v,validate=True)
            return AESGCM(user).decrypt(b[:12],b[12:],song_id.encode())
        key,iv=unwrap(str(j["key"])),unwrap(str(j["iv"]))
        return (key,iv),{"ok":True,"response_sha256":hashlib.sha256(p.stdout.encode()).hexdigest()}
    except Exception as e: return None,{"ok":False,"error":repr(e)}

def decrypt(src,dst,song_id):
    ki,ev=rights(song_id)
    if not ki: return False,ev
    key,iv=ki
    try:
        dec=Cipher(algorithms.AES(key),modes.CTR(iv)).decryptor()
        with src.open("rb") as fi,dst.open("wb") as fo:
            for b in iter(lambda:fi.read(8*1024*1024),b""): fo.write(dec.update(b))
            fo.write(dec.finalize())
        head=dst.read_bytes()[:12]
        if len(head)<12 or head[4:8]!=b"ftyp": return False,{**ev,"error":"no ftyp"}
        return True,ev
    except Exception as e: return False,{**ev,"error":repr(e)}

def probe(path):
    p=sh(["ffprobe","-v","error","-select_streams","a:0","-show_entries","format=duration:stream=codec_name,sample_rate,channels","-of","json",str(path)],90)
    if p.returncode: return None,p.stderr[-1200:]
    try:
        j=json.loads(p.stdout); st=(j.get("streams") or [None])[0]
        if not st: return None,"no audio stream"
        return {"duration":float((j.get("format") or {}).get("duration") or 0),"codec":st.get("codec_name"),
                "sample_rate":int(st.get("sample_rate") or 0),"channels":int(st.get("channels") or 0)},""
    except Exception as e:return None,repr(e)

def pcm_segment(path,start,dur=SEG):
    p=sh(["ffmpeg","-nostdin","-v","error","-ss",f"{max(0,start):.3f}","-t",f"{dur:.3f}","-i",str(path),
          "-map","0:a:0","-ac","1","-ar",str(SR),"-f","f32le","-"],120,binary=True)
    if p.returncode: return None,p.stderr.decode("utf-8","replace")[-1200:]
    y=np.frombuffer(p.stdout,dtype="<f4").astype(np.float64)
    return y,""

def frames(y,n=2048,hop=512):
    if len(y)<n: y=np.pad(y,(0,n-len(y)))
    count=1+(len(y)-n)//hop
    idx=np.arange(n)[None,:]+hop*np.arange(count)[:,None]
    return y[idx]

def mel_fb(sr,nfft,nmels=32,fmin=30,fmax=10000):
    def hzmel(h): return 2595*np.log10(1+h/700)
    def melhz(m): return 700*(10**(m/2595)-1)
    m=np.linspace(hzmel(fmin),hzmel(min(fmax,sr/2)),nmels+2)
    hz=melhz(m); bins=np.floor((nfft+1)*hz/sr).astype(int)
    fb=np.zeros((nmels,nfft//2+1))
    for i in range(nmels):
        a,b,c=bins[i],bins[i+1],bins[i+2]
        if b>a: fb[i,a:b]=(np.arange(a,b)-a)/(b-a)
        if c>b: fb[i,b:c]=(c-np.arange(b,c))/(c-b)
    return fb

def segment_features(y):
    eps=1e-12; F=frames(y); win=np.hanning(F.shape[1]); W=F*win
    mag=np.abs(np.fft.rfft(W,axis=1))+eps; powr=mag**2
    freqs=np.fft.rfftfreq(F.shape[1],1/SR)
    den=mag.sum(axis=1)+eps
    cent=(mag*freqs).sum(axis=1)/den
    band=np.sqrt(((freqs-cent[:,None])**2*mag).sum(axis=1)/den)
    cs=np.cumsum(mag,axis=1); thresh=.85*cs[:,-1,None]
    ridx=(cs>=thresh).argmax(axis=1); roll=freqs[ridx]
    flat=np.exp(np.log(mag).mean(axis=1))/(mag.mean(axis=1)+eps)
    rms=np.sqrt((F**2).mean(axis=1)+eps); peak=np.max(np.abs(F),axis=1)
    zcr=(np.diff(np.signbit(F),axis=1)!=0).mean(axis=1)
    nm=mag/(np.linalg.norm(mag,axis=1,keepdims=True)+eps)
    flux=np.sqrt(np.maximum(np.diff(nm,axis=0),0)**2).sum(axis=1) if len(nm)>1 else np.zeros(1)
    # tempo proxy from spectral flux autocorrelation
    env=np.r_[0,flux]; env=np.maximum(env-np.median(env),0)
    ac=np.correlate(env,env,mode="full")[len(env)-1:]
    fps=SR/512
    lo=max(1,int(fps*60/200)); hi=min(len(ac)-1,int(fps*60/60))
    if hi>lo and np.any(ac[lo:hi+1]>0):
        lag=lo+int(np.argmax(ac[lo:hi+1])); tempo=60*fps/lag; tempo_conf=float(ac[lag]/(ac[0]+eps))
    else: tempo=0.; tempo_conf=0.
    # chroma
    chrom=np.zeros(12)
    avg=mag.mean(axis=0)
    valid=(freqs>=40)&(freqs<=5000)
    ff=freqs[valid]; aa=avg[valid]
    midi=np.rint(69+12*np.log2(ff/440)).astype(int)
    for pc,val in zip(midi%12,aa): chrom[pc]+=val
    chrom=chrom/(chrom.sum()+eps)
    ent=float(-(chrom*np.log(chrom+eps)).sum()/np.log(12))
    major=np.array([6.35,2.23,3.48,2.33,4.38,4.09,2.52,5.19,2.39,3.66,2.29,2.88])
    minor=np.array([6.33,2.68,3.52,5.38,2.60,3.53,2.54,4.75,3.98,2.69,3.34,3.17])
    scores=[]
    for mode,prof in [("major",major),("minor",minor)]:
        for k in range(12):
            scores.append((float(np.corrcoef(chrom,np.roll(prof,k))[0,1]),k,mode))
    scores.sort(reverse=True); keyscore,keypc,keymode=scores[0]
    # MFCC
    fb=mel_fb(SR,2048); mel=np.maximum(powr@fb.T,eps); logmel=np.log(mel)
    mf=dct(logmel,type=2,axis=1,norm="ortho")[:,:13]
    feat={
      "rms_mean":float(rms.mean()),"rms_std":float(rms.std()),"rms_p10":float(np.quantile(rms,.1)),"rms_p90":float(np.quantile(rms,.9)),
      "crest_mean":float((peak/(rms+eps)).mean()),"centroid_mean":float(cent.mean()),"bandwidth_mean":float(band.mean()),
      "rolloff85_mean":float(roll.mean()),"flatness_mean":float(flat.mean()),"zcr_mean":float(zcr.mean()),
      "flux_mean":float(flux.mean()),"tempo_proxy":float(tempo),"tempo_conf":tempo_conf,
      "chroma_entropy":ent,"key_pc":int(keypc),"key_mode":keymode,"key_conf":float(keyscore)}
    for i in range(13):
        feat[f"mfcc{i+1}_mean"]=float(mf[:,i].mean()); feat[f"mfcc{i+1}_std"]=float(mf[:,i].std())
    feat["chroma"]=chrom.tolist()
    return feat

def analyze(path):
    pr,err=probe(path)
    if not pr:return None,err
    dur=pr["duration"]; starts=[0,max(0,dur/2-SEG/2),max(0,dur-SEG)]
    segs=[]
    for s in starts:
        y,e=pcm_segment(path,s)
        if y is None or len(y)<100:return None,e or "short decode"
        segs.append(segment_features(y))
    scalar=["rms_mean","rms_std","crest_mean","centroid_mean","bandwidth_mean","rolloff85_mean","flatness_mean","zcr_mean","flux_mean","tempo_proxy","tempo_conf","chroma_entropy","key_conf"]
    out={**pr}
    for name,seg in zip(["first","middle","last"],segs):
        for k in scalar: out[f"{name}_{k}"]=seg[k]
    for k in scalar:
        out[f"sample_mean_{k}"]=float(np.mean([x[k] for x in segs]))
        out[f"sample_std_{k}"]=float(np.std([x[k] for x in segs]))
    # MFCC/chroma averages
    for i in range(13):
        for stat in ("mean","std"):
            k=f"mfcc{i+1}_{stat}"; out[f"sample_{k}"]=float(np.mean([x[k] for x in segs]))
    chrom=np.mean(np.array([x["chroma"] for x in segs]),axis=0); chrom=chrom/(chrom.sum()+1e-12)
    for i,v in enumerate(chrom): out[f"chroma_{i}"]=float(v)
    # section contrast using interpretable normalized segment vector
    V=[]
    for x in segs:
        V.append(np.array([math.log(x["rms_mean"]+1e-9),x["centroid_mean"]/5000,x["bandwidth_mean"]/5000,
                           x["rolloff85_mean"]/10000,x["flatness_mean"],x["zcr_mean"],x["flux_mean"],x["chroma_entropy"]]))
    ds=[]
    for i in range(3):
      for j in range(i+1,3):
        a,b=V[i],V[j]; ds.append(float(1-np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b)+1e-12)))
    out["section_contrast_mean"]=float(np.mean(ds)); out["section_contrast_max"]=float(np.max(ds))
    out["energy_arc_middle_minus_ends"]=float(segs[1]["rms_mean"]-(segs[0]["rms_mean"]+segs[2]["rms_mean"])/2)
    out["energy_end_minus_start"]=float(segs[2]["rms_mean"]-segs[0]["rms_mean"])
    return out,""

ap=argparse.ArgumentParser()
ap.add_argument("--targets",required=True); ap.add_argument("--shard",type=int,required=True); ap.add_argument("--shards",type=int,required=True); ap.add_argument("--out",required=True)
a=ap.parse_args(); out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True)
ids=[x.strip() for x in open(a.targets) if x.strip()]
mine=[x for i,x in enumerate(ids) if i%a.shards==a.shard]
fp=out/f"features-{a.shard:02d}.jsonl"; summary={"shard":a.shard,"targets":len(mine),"ok":0,"download_fail":0,"rights_fail":0,"analysis_fail":0}
with fp.open("w",encoding="utf-8") as fo, tempfile.TemporaryDirectory(prefix="mir-") as td:
  td=pathlib.Path(td)
  for n,song in enumerate(mine):
    enc=td/(song+".enc"); dec=td/(song+".m4a")
    rec={"id":song,"shard":a.shard}
    ok,e=download(CF.format(id=song),enc)
    if not ok:
        rec.update({"ok":False,"stage":"download","error":e}); summary["download_fail"]+=1
    else:
        rec["transport_bytes"]=enc.stat().st_size; rec["transport_sha256"]=hashlib.sha256(enc.read_bytes()).hexdigest()
        dok,rev=decrypt(enc,dec,song); rec["rights"]=rev
        if not dok:
            rec.update({"ok":False,"stage":"rights","error":rev.get("error","rights failed")}); summary["rights_fail"]+=1
        else:
            feat,err=analyze(dec)
            if feat is None:
                rec.update({"ok":False,"stage":"analysis","error":err}); summary["analysis_fail"]+=1
            else:
                rec.update({"ok":True,"audio_sha256":hashlib.sha256(dec.read_bytes()).hexdigest(),"features":feat}); summary["ok"]+=1
    fo.write(json.dumps(rec,separators=(",",":"))+"\n"); fo.flush()
    for p in (enc,dec):
        try:p.unlink()
        except FileNotFoundError:pass
    if (n+1)%25==0: print(a.shard,n+1,len(mine),summary,flush=True)
(out/f"summary-{a.shard:02d}.json").write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
