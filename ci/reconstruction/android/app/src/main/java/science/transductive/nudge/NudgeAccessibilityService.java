package science.transductive.nudge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import science.transductive.nudge.core.AutomationPlanner;

public final class NudgeAccessibilityService extends AccessibilityService {
    public enum Outcome { APPLIED, NOT_APPLIED, UNKNOWN_OUTCOME, STALE_SNAPSHOT }
    public static final class ActionResult {
        public final Outcome outcome; public final String evidence;
        ActionResult(Outcome o,String e){outcome=o;evidence=e;}
        public static ActionResult of(Outcome o,String e){return new ActionResult(o,e);}
    }
    public static final class Snapshot {
        public final AutomationPlanner.Snapshot model;
        final List<AccessibilityNodeInfo> handles;
        final long serviceToken;
        Snapshot(AutomationPlanner.Snapshot m,List<AccessibilityNodeInfo> h,long token){model=m;handles=h;serviceToken=token;}
        AccessibilityNodeInfo handle(int index){return index>=0&&index<handles.size()?handles.get(index):null;}
    }

    private static final AtomicLong TOKENS=new AtomicLong();
    private static volatile NudgeAccessibilityService instance;
    private final AtomicLong generation=new AtomicLong();
    private final Object changed=new Object();
    private Handler main;
    private long serviceToken;

    @Override protected void onServiceConnected(){
        super.onServiceConnected();main=new Handler(Looper.getMainLooper());serviceToken=TOKENS.incrementAndGet();instance=this;bump();
    }
    @Override public boolean onUnbind(android.content.Intent i){if(instance==this)instance=null;bump();return super.onUnbind(i);}
    @Override public void onDestroy(){if(instance==this)instance=null;bump();super.onDestroy();}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){bump();}
    @Override public void onInterrupt(){}

    private void bump(){generation.incrementAndGet();synchronized(changed){changed.notifyAll();}}
    public static NudgeAccessibilityService current(){return instance;}
    public long generation(){return generation.get();}

    public boolean awaitChange(long after,long timeoutMs){
        long end=SystemClock.elapsedRealtime()+Math.max(0,timeoutMs);
        synchronized(changed){
            while(generation.get()<=after){
                long left=end-SystemClock.elapsedRealtime();if(left<=0)return false;
                try{changed.wait(left);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            }
        }
        return true;
    }

    public Snapshot snapshot(final int maxNodes){
        return onMain(new Callable<Snapshot>(){public Snapshot call(){
            AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return null;
            final long g=generation.get();final int limit=Math.max(1,Math.min(240,maxNodes));
            ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<AccessibilityNodeInfo>();
            q.add(root);List<AccessibilityNodeInfo> handles=new ArrayList<AccessibilityNodeInfo>();
            List<AutomationPlanner.Node> nodes=new ArrayList<AutomationPlanner.Node>();
            while(!q.isEmpty()&&nodes.size()<limit){
                AccessibilityNodeInfo n=q.removeFirst();if(n==null)continue;
                Rect b=new Rect();n.getBoundsInScreen(b);int idx=nodes.size();
                nodes.add(new AutomationPlanner.Node(idx,str(n.getText()),str(n.getContentDescription()),
                    str(n.getClassName()),str(n.getViewIdResourceName()),b.left,b.top,b.right,b.bottom,
                    n.isEditable(),n.isClickable(),n.isEnabled(),n.isPassword(),n.isFocused()));
                handles.add(n);
                int children=Math.min(64,n.getChildCount());
                for(int i=0;i<children;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)q.addLast(c);}
            }
            Point p=new Point();WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            @SuppressWarnings("deprecation") Display d=wm.getDefaultDisplay();
            @SuppressWarnings("deprecation") Point ignored=p;d.getRealSize(p);
            AutomationPlanner.Snapshot model=new AutomationPlanner.Snapshot(str(root.getPackageName()),g,p.x,p.y,nodes);
            return new Snapshot(model,handles,serviceToken);
        }},3000);
    }

    public ActionResult setText(final Snapshot s,final int index,final String text){
        return nodeAction(s,index,new NodeCall(){public boolean run(AccessibilityNodeInfo n){
            if(!n.isEditable()||n.isPassword()||!n.isEnabled())return false;
            Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);
        }},"set_text");
    }

    public ActionResult click(final Snapshot s,final int index){
        return nodeAction(s,index,new NodeCall(){public boolean run(AccessibilityNodeInfo n){
            return n.isEnabled()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }},"click");
    }

    private interface NodeCall{boolean run(AccessibilityNodeInfo n);}
    private ActionResult nodeAction(final Snapshot s,final int index,final NodeCall call,final String name){
        if(s==null||s.serviceToken!=serviceToken||s.model.generation!=generation.get())
            return ActionResult.of(Outcome.STALE_SNAPSHOT,name+":snapshot_generation_changed");
        final AccessibilityNodeInfo n=s.handle(index);if(n==null)return ActionResult.of(Outcome.NOT_APPLIED,name+":no_node");
        Boolean accepted=onMain(new Callable<Boolean>(){public Boolean call(){
            if(s.serviceToken!=serviceToken||s.model.generation!=generation.get())return null;
            try{if(!n.refresh())return Boolean.FALSE;return call.run(n);}catch(Throwable t){return null;}
        }},3000);
        if(accepted==null)return ActionResult.of(Outcome.UNKNOWN_OUTCOME,name+":framework_outcome_unknown");
        return accepted.booleanValue()?ActionResult.of(Outcome.UNKNOWN_OUTCOME,name+":accepted_reobserve"):
            ActionResult.of(Outcome.NOT_APPLIED,name+":rejected");
    }

    public ActionResult tap(final float x,final float y,long timeoutMs){
        if(Build.VERSION.SDK_INT<24)return ActionResult.of(Outcome.NOT_APPLIED,"gesture_api_unavailable");
        Path p=new Path();p.moveTo(x,y);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,80)).build();
        final CountDownLatch latch=new CountDownLatch(1);final Outcome[] out={Outcome.UNKNOWN_OUTCOME};
        boolean dispatched;
        try{
            dispatched=dispatchGesture(g,new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription d){out[0]=Outcome.APPLIED;latch.countDown();}
                @Override public void onCancelled(GestureDescription d){out[0]=Outcome.NOT_APPLIED;latch.countDown();}
            },main);
        }catch(Throwable t){return ActionResult.of(Outcome.UNKNOWN_OUTCOME,"gesture_exception_reobserve");}
        if(!dispatched)return ActionResult.of(Outcome.NOT_APPLIED,"gesture_not_dispatched");
        try{if(!latch.await(Math.max(1,timeoutMs),TimeUnit.MILLISECONDS))return ActionResult.of(Outcome.UNKNOWN_OUTCOME,"gesture_callback_timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();return ActionResult.of(Outcome.UNKNOWN_OUTCOME,"gesture_interrupted");}
        return ActionResult.of(out[0],"gesture_callback");
    }

    public ActionResult globalAction(int action){
        try{return performGlobalAction(action)?ActionResult.of(Outcome.UNKNOWN_OUTCOME,"global_action_accepted_reobserve"):
            ActionResult.of(Outcome.NOT_APPLIED,"global_action_rejected");}
        catch(Throwable t){return ActionResult.of(Outcome.UNKNOWN_OUTCOME,"global_action_exception_reobserve");}
    }

    public Bitmap screenshot(final int windowId,long timeoutMs){
        if(Build.VERSION.SDK_INT<30)return null;
        final CountDownLatch latch=new CountDownLatch(1);final Bitmap[] out={null};
        TakeScreenshotCallback cb=new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult r){
                try{
                    HardwareBuffer hb=r.getHardwareBuffer();
                    Bitmap hw=Bitmap.wrapHardwareBuffer(hb,r.getColorSpace());
                    if(hw!=null)out[0]=hw.copy(Bitmap.Config.ARGB_8888,false);
                    hb.close();
                }catch(Throwable ignored){}finally{latch.countDown();}
            }
            @Override public void onFailure(int errorCode){latch.countDown();}
        };
        try{
            if(Build.VERSION.SDK_INT>=34&&windowId>=0)takeScreenshotOfWindow(windowId,getMainExecutor(),cb);
            else takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),cb);
            if(!latch.await(Math.max(1,timeoutMs),TimeUnit.MILLISECONDS))return null;
        }catch(Throwable t){return null;}
        return out[0];
    }

    private <T>T onMain(final Callable<T> c,long timeoutMs){
        if(main==null)return null;
        if(Looper.myLooper()==Looper.getMainLooper()){try{return c.call();}catch(Exception e){return null;}}
        FutureTask<T> f=new FutureTask<T>(c);if(!main.post(f))return null;
        try{return f.get(Math.max(1,timeoutMs),TimeUnit.MILLISECONDS);}
        catch(Throwable t){f.cancel(false);return null;}
    }
    private static String str(Object x){return x==null?"":String.valueOf(x);}
}
