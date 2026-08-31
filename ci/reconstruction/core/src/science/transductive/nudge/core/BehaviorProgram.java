package science.transductive.nudge.core;

import java.util.*;

public final class BehaviorProgram {
    public static final String VERSION="BEHAVIOR_PROGRAM/1";
    public enum Op { CHATGPT_PROMPT, NUDGE, DELAY, EMIT }
    public static final class Step {
        public final Op op;public final String text,targetPackage,key;public final long delayMs;
        Step(Op o,String t,String p,String k,long d){op=o;text=t;targetPackage=p;key=k;delayMs=d;}
    }
    public final List<Step> steps;
    private BehaviorProgram(List<Step>s){steps=Collections.unmodifiableList(new ArrayList<Step>(s));}

    @SuppressWarnings("unchecked")
    public static BehaviorProgram parse(String json){
        Object r=MiniJson.parse(json);if(!(r instanceof Map))throw new IllegalArgumentException("program root");
        Map<String,Object>m=(Map<String,Object>)r;if(!VERSION.equals(m.get("version")))throw new IllegalArgumentException("unsupported program version");
        Object raw=m.get("steps");if(!(raw instanceof List))throw new IllegalArgumentException("missing steps");
        List<?>a=(List<?>)raw;if(a.isEmpty()||a.size()>32)throw new IllegalArgumentException("bad step count");
        List<Step>out=new ArrayList<Step>();
        long delayBudget=0;
        for(Object x:a){
            if(!(x instanceof Map))throw new IllegalArgumentException("step object");
            Map<String,Object>s=(Map<String,Object>)x;Op op;
            try{op=Op.valueOf(String.valueOf(s.get("op")));}catch(Exception e){throw new IllegalArgumentException("unsupported op");}
            String text=str(s,"text",""),pkg=str(s,"targetPackage","com.openai.chatgpt"),key=str(s,"key","");
            long delay=num(s,"delayMs",0);
            if(text.length()>8000||pkg.length()>240||key.length()>160)throw new IllegalArgumentException("step field too long");
            if(op==Op.CHATGPT_PROMPT&&text.trim().isEmpty())throw new IllegalArgumentException("empty chat prompt");
            if(op==Op.NUDGE&&text.trim().isEmpty())throw new IllegalArgumentException("empty nudge");
            if(op==Op.DELAY&&(delay<0||delay>30000))throw new IllegalArgumentException("bad delay");
            delayBudget+=delay;if(delayBudget>60000)throw new IllegalArgumentException("delay budget");
            out.add(new Step(op,text,pkg,key,delay));
        }
        return new BehaviorProgram(out);
    }
    private static String str(Map<String,Object>m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
    private static long num(Map<String,Object>m,String k,long d){Object v=m.get(k);return v instanceof Number?((Number)v).longValue():d;}
}
