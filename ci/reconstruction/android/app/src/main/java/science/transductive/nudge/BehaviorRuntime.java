package science.transductive.nudge;

import android.content.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import science.transductive.nudge.core.*;

public final class BehaviorRuntime {
    public enum Status { APPLIED, NOT_APPLIED, UNKNOWN_OUTCOME }
    public static final class Result {
        public final Status status;public final String bundleId,evidence;public final Map<String,String> outputs;
        Result(Status s,String b,String e,Map<String,String>o){status=s;bundleId=b;evidence=e;outputs=Collections.unmodifiableMap(new LinkedHashMap<String,String>(o));}
    }
    private BehaviorRuntime(){}

    public static synchronized Result run(Context c,String signedBundleJson){
        long now=System.currentTimeMillis();
        SignedBehaviorBundle.Verified b;
        try{b=SignedBehaviorBundle.verify(signedBundleJson,now,TrustedBehaviorKeys.all(c));}
        catch(IllegalArgumentException e){return new Result(Status.NOT_APPLIED,"","verify:"+e.getMessage(),Collections.<String,String>emptyMap());}
        File journal=file(c,b.bundleId);
        if(journal.isFile())return new Result(Status.UNKNOWN_OUTCOME,b.bundleId,"bundle_already_journaled_reobserve_or_issue_new_bundle",Collections.<String,String>emptyMap());

        BehaviorProgram p;
        try{p=BehaviorProgram.parse(b.payloadJson);}catch(IllegalArgumentException e){return new Result(Status.NOT_APPLIED,b.bundleId,"program:"+e.getMessage(),Collections.<String,String>emptyMap());}
        LinkedHashMap<String,String> outputs=new LinkedHashMap<String,String>();String last="";
        write(journal,state(b,0,"STARTED","",outputs));
        for(int i=0;i<p.steps.size();i++){
            BehaviorProgram.Step step=p.steps.get(i);
            write(journal,state(b,i,"PREPARED",step.op.name(),outputs));
            switch(step.op){
                case DELAY:
                    try{Thread.sleep(step.delayMs);}catch(InterruptedException e){Thread.currentThread().interrupt();write(journal,state(b,i,"UNKNOWN_OUTCOME","delay_interrupted",outputs));return new Result(Status.UNKNOWN_OUTCOME,b.bundleId,"delay_interrupted",outputs);}
                    break;
                case CHATGPT_PROMPT:
                    ChatGptAutomationEngine.Result ar=ChatGptAutomationEngine.run(c,b.bundleId+"-step-"+i,step.targetPackage,step.text,120_000);
                    if(ar.status!=ChatGptAutomationEngine.Status.APPLIED){
                        Status s=ar.status==ChatGptAutomationEngine.Status.NOT_APPLIED?Status.NOT_APPLIED:Status.UNKNOWN_OUTCOME;
                        write(journal,state(b,i,s.name(),"chatgpt:"+ar.evidence,outputs));return new Result(s,b.bundleId,"chatgpt:"+ar.evidence,outputs);
                    }
                    last=ar.response;break;
                case EMIT:
                    if(step.key.trim().isEmpty()){write(journal,state(b,i,"NOT_APPLIED","emit_key_missing",outputs));return new Result(Status.NOT_APPLIED,b.bundleId,"emit_key_missing",outputs);}
                    outputs.put(step.key,last);break;
                case NUDGE:
                    Intent in=new Intent(c,InterruptionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    in.putExtra("prompt",step.text);in.putExtra("mode","choice");
                    try{c.startActivity(in);}catch(Throwable t){write(journal,state(b,i,"NOT_APPLIED","nudge_launch_failed",outputs));return new Result(Status.NOT_APPLIED,b.bundleId,"nudge_launch_failed",outputs);}
                    break;
            }
            write(journal,state(b,i+1,"STEP_APPLIED",step.op.name(),outputs));
        }
        write(journal,state(b,p.steps.size(),"COMPLETE","",outputs));
        return new Result(Status.APPLIED,b.bundleId,"signed_behavior_complete",outputs);
    }

    private static File file(Context c,String id){File d=new File(c.getFilesDir(),"behavior-journal");d.mkdirs();return new File(d,id.replaceAll("[^A-Za-z0-9._-]","_")+".json");}
    private static String state(SignedBehaviorBundle.Verified b,int step,String status,String evidence,Map<String,String>outputs){
        Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("schema","BEHAVIOR_RUNTIME_JOURNAL/1");m.put("bundleId",b.bundleId);m.put("payloadSha256",b.payloadSha256);m.put("step",step);m.put("status",status);m.put("evidence",evidence);m.put("atMs",System.currentTimeMillis());m.put("outputs",outputs);return MiniJson.stringify(m);
    }
    private static void write(File target,String text){
        try{File tmp=new File(target.getParentFile(),target.getName()+".tmp");try(FileOutputStream o=new FileOutputStream(tmp)){o.write(text.getBytes(StandardCharsets.UTF_8));o.getFD().sync();}if(!tmp.renameTo(target))throw new IOException("journal rename");}
        catch(IOException e){throw new IllegalStateException(e);}
    }
}
