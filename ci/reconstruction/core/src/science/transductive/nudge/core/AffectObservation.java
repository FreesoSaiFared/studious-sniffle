package science.transductive.nudge.core;

import java.util.*;

public final class AffectObservation {
    public final String source,modelVersion,provenance;
    public final long atMs;
    public final double quality;
    public final Map<String,Double> features,hypotheses;
    public AffectObservation(String source,String modelVersion,String provenance,long atMs,double quality,Map<String,Double>features,Map<String,Double>hypotheses){
        this.source=req(source);this.modelVersion=req(modelVersion);this.provenance=req(provenance);this.atMs=atMs;this.quality=unit(quality,"quality");
        this.features=freeze(features,false);this.hypotheses=freeze(hypotheses,true);
    }
    public String json(){
        Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("schema","AFFECT_OBSERVATION/1");m.put("source",source);m.put("modelVersion",modelVersion);m.put("provenance",provenance);m.put("atMs",atMs);m.put("quality",quality);m.put("features",features);m.put("hypotheses",hypotheses);m.put("authority","UNCERTAIN_OBSERVATION_NOT_GROUND_TRUTH");return MiniJson.stringify(m);
    }
    private static Map<String,Double> freeze(Map<String,Double>in,boolean scores){LinkedHashMap<String,Double>o=new LinkedHashMap<String,Double>();if(in!=null)for(Map.Entry<String,Double>e:in.entrySet()){String k=req(e.getKey());double v=e.getValue()==null?Double.NaN:e.getValue();if(!Double.isFinite(v))throw new IllegalArgumentException("non-finite "+k);if(scores)unit(v,k);o.put(k,v);}return Collections.unmodifiableMap(o);}
    private static double unit(double v,String n){if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException(n+" outside 0..1");return v;}
    private static String req(String s){if(s==null||s.trim().isEmpty())throw new IllegalArgumentException("blank field");return s;}
}
