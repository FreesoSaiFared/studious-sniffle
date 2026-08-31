package science.transductive.nudge;

import android.content.*;
import android.os.Looper;
import android.os.SystemClock;
import java.util.*;
import science.transductive.nudge.core.AutomationPlanner;
import science.transductive.nudge.core.MiniJson;

public final class ChatGptAutomationEngine {
    public enum Status { APPLIED, NOT_APPLIED, UNKNOWN_OUTCOME }
    public static final class Result {
        public final String jobId,packageName,prompt,response,evidence,responseSha256;
        public final Status status;public final long startedAtMs,finishedAtMs;
        Result(String j,String p,String prompt,String response,String evidence,Status status,long start,long end){
            this.jobId=j;this.packageName=p;this.prompt=prompt;this.response=response;this.evidence=evidence;this.status=status;
            this.startedAtMs=start;this.finishedAtMs=end;this.responseSha256=AutomationPlanner.fingerprint(response);
        }
        public String json(){
            Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("schema","CHATGPT_AUTOMATION_RECEIPT/1");m.put("jobId",jobId);
            m.put("packageName",packageName);m.put("status",status.name());m.put("startedAtMs",startedAtMs);m.put("finishedAtMs",finishedAtMs);
            m.put("promptSha256",AutomationPlanner.fingerprint(prompt));m.put("responseSha256",responseSha256);m.put("response",response);m.put("evidence",evidence);
            return MiniJson.stringify(m);
        }
    }
    private ChatGptAutomationEngine(){}

    public static Result run(Context c,String jobId,String packageName,String prompt,long timeoutMs){
        long start=System.currentTimeMillis(),deadline=SystemClock.elapsedRealtime()+Math.max(10_000,Math.min(180_000,timeoutMs));
        if(Looper.myLooper()==Looper.getMainLooper())return finish(c,new Result(jobId,packageName,prompt,"","engine_called_on_main_thread",Status.NOT_APPLIED,start,System.currentTimeMillis()));
        if(prompt==null||prompt.trim().isEmpty())return finish(c,new Result(jobId,packageName,prompt,"","empty_prompt",Status.NOT_APPLIED,start,System.currentTimeMillis()));
        NudgeAccessibilityService svc=NudgeAccessibilityService.current();
        if(svc==null)return finish(c,new Result(jobId,packageName,prompt,"","accessibility_service_not_connected",Status.NOT_APPLIED,start,System.currentTimeMillis()));
        Intent launch=c.getPackageManager().getLaunchIntentForPackage(packageName);
        if(launch==null)return finish(c,new Result(jobId,packageName,prompt,"","target_package_not_launchable",Status.NOT_APPLIED,start,System.currentTimeMillis()));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try{c.startActivity(launch);}catch(Throwable t){return finish(c,new Result(jobId,packageName,prompt,"","target_launch_failed",Status.NOT_APPLIED,start,System.currentTimeMillis()));}

        NudgeAccessibilityService.Snapshot snap=waitTarget(svc,packageName,deadline);
        if(snap==null)return finish(c,new Result(jobId,packageName,prompt,"","target_not_foreground",Status.NOT_APPLIED,start,System.currentTimeMillis()));
        int composer=AutomationPlanner.findComposer(snap.model);
        if(composer<0)return finish(c,new Result(jobId,packageName,prompt,"","composer_not_found",Status.NOT_APPLIED,start,System.currentTimeMillis()));

        NudgeAccessibilityService.ActionResult set=svc.setText(snap,composer,prompt);
        if(set.outcome==NudgeAccessibilityService.Outcome.NOT_APPLIED)
            return finish(c,new Result(jobId,packageName,prompt,"","set_text_rejected",Status.NOT_APPLIED,start,System.currentTimeMillis()));

        NudgeAccessibilityService.Snapshot textVerified=waitComposerText(svc,packageName,prompt,deadline);
        if(textVerified==null)
            return finish(c,new Result(jobId,packageName,prompt,"","set_text_outcome_unknown",Status.UNKNOWN_OUTCOME,start,System.currentTimeMillis()));

        int composer2=AutomationPlanner.findComposer(textVerified.model);
        AutomationPlanner.Node composerNode=textVerified.model.byIndex(composer2);
        int send=AutomationPlanner.findSend(textVerified.model,composerNode);
        if(send<0)return finish(c,new Result(jobId,packageName,prompt,"","send_control_not_found",Status.NOT_APPLIED,start,System.currentTimeMillis()));

        NudgeAccessibilityService.Snapshot beforeSend=textVerified;
        long beforeGeneration=svc.generation();
        NudgeAccessibilityService.ActionResult click=svc.click(textVerified,send);
        if(click.outcome==NudgeAccessibilityService.Outcome.NOT_APPLIED)
            return finish(c,new Result(jobId,packageName,prompt,"","send_rejected",Status.NOT_APPLIED,start,System.currentTimeMillis()));

        if(!svc.awaitChange(beforeGeneration,Math.min(5000,remaining(deadline))))
            return finish(c,new Result(jobId,packageName,prompt,"","send_outcome_unknown_no_ui_change",Status.UNKNOWN_OUTCOME,start,System.currentTimeMillis()));

        List<String> samples=new ArrayList<String>();long firstCandidateAt=0;
        while(SystemClock.elapsedRealtime()<deadline){
            NudgeAccessibilityService.Snapshot now=svc.snapshot(240);
            if(now==null||!AutomationPlanner.packageMatches(now.model,packageName)){
                sleep(250);continue;
            }
            String candidate=AutomationPlanner.projectResponse(beforeSend.model,now.model,prompt);
            if(!candidate.isEmpty()){
                if(firstCandidateAt==0)firstCandidateAt=SystemClock.elapsedRealtime();
                samples.add(candidate);while(samples.size()>3)samples.remove(0);
                if(AutomationPlanner.stable(samples)&&SystemClock.elapsedRealtime()-firstCandidateAt>=800&&!hasGeneratingControl(now.model)){
                    return finish(c,new Result(jobId,packageName,prompt,candidate,"tree_response_stable_3_samples",Status.APPLIED,start,System.currentTimeMillis()));
                }
            }else{samples.clear();firstCandidateAt=0;}
            long g=svc.generation();svc.awaitChange(g,450);
        }
        String last=samples.isEmpty()?"":samples.get(samples.size()-1);
        return finish(c,new Result(jobId,packageName,prompt,last,"response_timeout_reobserve_required",Status.UNKNOWN_OUTCOME,start,System.currentTimeMillis()));
    }

    private static NudgeAccessibilityService.Snapshot waitTarget(NudgeAccessibilityService svc,String pkg,long deadline){
        while(SystemClock.elapsedRealtime()<deadline){
            NudgeAccessibilityService.Snapshot s=svc.snapshot(240);
            if(s!=null&&AutomationPlanner.packageMatches(s.model,pkg))return s;
            long g=svc.generation();svc.awaitChange(g,400);
        }return null;
    }
    private static NudgeAccessibilityService.Snapshot waitComposerText(NudgeAccessibilityService svc,String pkg,String prompt,long deadline){
        long local=Math.min(deadline,SystemClock.elapsedRealtime()+5000);
        while(SystemClock.elapsedRealtime()<local){
            NudgeAccessibilityService.Snapshot s=svc.snapshot(240);
            if(s!=null&&AutomationPlanner.packageMatches(s.model,pkg)){
                int i=AutomationPlanner.findComposer(s.model);AutomationPlanner.Node n=s.model.byIndex(i);
                if(n!=null&&prompt.equals(n.text))return s;
            }
            long g=svc.generation();svc.awaitChange(g,250);
        }return null;
    }
    private static boolean hasGeneratingControl(AutomationPlanner.Snapshot s){
        for(AutomationPlanner.Node n:s.nodes){String x=n.semantic();if(x.contains("stop generating")||x.contains("cancel response"))return true;}return false;
    }
    private static long remaining(long deadline){return Math.max(1,deadline-SystemClock.elapsedRealtime());}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static Result finish(Context c,Result r){AutomationJournal.persist(c,r.jobId,r.json());return r;}
}
