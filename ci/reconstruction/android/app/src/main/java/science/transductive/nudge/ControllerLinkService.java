package science.transductive.nudge;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import science.transductive.nudge.core.*;
import science.transductive.nudge.core.Models.*;

public final class ControllerLinkService extends Service {
    public static final String CH="nudge_link";
    private final AtomicBoolean stop=new AtomicBoolean();
    private Thread loop;

    @Override public void onCreate(){super.onCreate();ensureChannel();}
    @Override public int onStartCommand(Intent in,int flags,int startId){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
        startForeground(3001,b.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("AI support link active").setContentText("Listening for user-authorized interventions").setOngoing(true).build());
        if(loop==null||!loop.isAlive()){stop.set(false);loop=new Thread(this::runLoop,"nudge-controller-link");loop.start();}
        return START_STICKY;
    }
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CH,"AI support link",NotificationManager.IMPORTANCE_LOW);c.setDescription("Visible status for the continuous user-authorized controller connection");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    private void runLoop(){
        long backoff=1000;
        while(!stop.get()){
            try{
                SharedPreferences p=getSharedPreferences("controller",MODE_PRIVATE);
                String base=p.getString("endpoint",""); String token=SecretStore.getToken(this); String device=DeviceIdentity.get(this);
                if(base.trim().isEmpty()){sleep(5000);continue;}
                WorkerClient w=new WorkerClient(base,token);
                Outbox.flush(this,w);
                String json=w.next(device);
                if(json==null||json.trim().isEmpty()||json.equals("{}")){sleep(1000);continue;}
                InteractionSpec s=Protocol.parseInteraction(json);
                Protocol.validateForNow(s,System.currentTimeMillis(),InteractionJournal.seen(this));
                String receiptId;
                Receipt r;
                if(s.triggerAtMs()>System.currentTimeMillis()+1500){
                    boolean exact=AlarmScheduler.schedule(this,s);
                    Plan plan=Protocol.plan(s,caps(false));
                    r=Protocol.receipt(s,ReceiptStatus.APPLIED,System.currentTimeMillis(),"alarm_scheduled exact="+exact,plan);
                    receiptId="receipt-"+s.interactionId()+"-scheduled";
                } else {
                    AttentionController.post(this,s);
                    Plan plan=Protocol.plan(s,caps(false));
                    r=Protocol.receipt(s,ReceiptStatus.APPLIED,System.currentTimeMillis(),"notification_posted",plan);
                    receiptId="receipt-"+s.interactionId()+"-posted";
                }
                Outbox.enqueueEvent(this,receiptId,Protocol.stringifyReceipt(r));
                if(!InteractionJournal.accept(this,s.interactionId())) continue;
                Outbox.flush(this,w);
                backoff=1000;
            }catch(Throwable e){sleep(backoff);backoff=Math.min(60_000,backoff*2);}
        }
    }
    private CapabilitySnapshot caps(boolean visible){return new CapabilitySnapshot(CapabilityProbe.notifications(this),CapabilityProbe.fullScreen(this),CapabilityProbe.exactAlarm(this),CapabilityProbe.policy(this),CapabilityProbe.microphone(this),CapabilityProbe.accessibility(this),visible);}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Override public void onDestroy(){stop.set(true);if(loop!=null)loop.interrupt();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
