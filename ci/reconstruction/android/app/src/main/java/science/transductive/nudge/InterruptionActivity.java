package science.transductive.nudge;

import android.app.*;
import android.os.*;
import android.content.*;
import android.media.MediaRecorder;
import android.view.*;
import android.widget.*;
import android.graphics.Typeface;
import java.io.File;
import java.util.UUID;
import science.transductive.nudge.core.*;
import science.transductive.nudge.core.Models.*;

public class InterruptionActivity extends Activity {
    LinearLayout root; CountDownTimer timer; InteractionSpec spec; MediaRecorder recorder; File audioFile;
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);loadSpec();if(spec.readyGate())showReady();else showInteraction();}
    void loadSpec(){String json=getIntent().getStringExtra("interactionJson");if(json!=null){spec=Protocol.parseInteraction(json);return;}long n=System.currentTimeMillis();String mode=getIntent().getStringExtra("mode");Capture cap="audio".equals(mode)?Capture.AUDIO:Capture.CHOICE;spec=new InteractionSpec(Protocol.VERSION,"local-"+n,n,n,n+300000,"Local demo","DO NOT THINK. READY?",true,getIntent().getStringExtra("prompt"),Attention.URGENT,cap,cap==Capture.CHOICE?java.util.Arrays.asList(new Choice("good","GOOD"),new Choice("curious","CURIOUS"),new Choice("calm","CALM"),new Choice("tense","TENSE"),new Choice("frozen","FROZEN"),new Choice("tired","TIRED")):java.util.Collections.<Choice>emptyList(),2000,false,false);}
    Button b(String s){Button x=new Button(this);x.setText(s);x.setTextSize(22);x.setAllCaps(false);x.setMinHeight(112);return x;}
    void base(String prompt){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(36,36,36,36);TextView t=new TextView(this);t.setText(prompt);t.setTextSize(30);t.setTypeface(Typeface.DEFAULT_BOLD);t.setGravity(Gravity.CENTER);root.addView(t,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);}
    void showReady(){base(spec.readyPrompt());Button yes=b("READY — SHOW IT");yes.setOnClickListener(v->showInteraction());root.addView(yes);Button snooze=b("SNOOZE");snooze.setOnClickListener(v->finish());root.addView(snooze);}
    void addDeadline(){if(spec.countdownMs()<=0)return;TextView d=new TextView(this);d.setTextSize(18);d.setGravity(Gravity.CENTER);root.addView(d,0);timer=new CountDownTimer(spec.countdownMs(),100){public void onTick(long m){d.setText("ANSWER — "+String.format("%.1f",m/1000.0)+"s");}public void onFinish(){d.setText("ANSWER NOW");}};timer.start();}
    void showInteraction(){base(spec.prompt());addDeadline();if(spec.capture()==Capture.CHOICE){for(Choice c:spec.choices()){Button q=b(c.label());q.setOnClickListener(v->sendChoice(c.id()));root.addView(q);}}else if(spec.capture()==Capture.AUDIO){showAudioControls();}else{Button done=b("DONE");done.setOnClickListener(v->{postSimple("done");finish();});root.addView(done);}Button snooze=b("SNOOZE");snooze.setOnClickListener(v->{postSimple("snooze");finish();});root.addView(snooze);Button stop=b("STOP");stop.setOnClickListener(v->{postSimple("stop");finish();});root.addView(stop);}
    void sendChoice(String id){String eventId="ev-"+UUID.randomUUID();ResponseEvent e=Protocol.choiceEvent(spec,eventId,id,System.currentTimeMillis());postEventAsync(Protocol.stringifyResponse(e),eventId);finish();}
    void postSimple(String action){String eventId="ev-"+UUID.randomUUID();String json="{\"protocol\":\""+Protocol.VERSION+"\",\"eventId\":\""+eventId+"\",\"interactionId\":\""+spec.interactionId()+"\",\"atMs\":"+System.currentTimeMillis()+",\"action\":\""+action+"\"}";postEventAsync(json,eventId);}
    WorkerClient client(){SharedPreferences p=getSharedPreferences("controller",MODE_PRIVATE);String base=p.getString("endpoint","");return base.trim().isEmpty()?null:new WorkerClient(base,SecretStore.getToken(this));}
    void postEventAsync(String json,String eventId){Outbox.enqueueEvent(this,eventId,json);WorkerClient c=client();if(c!=null)new Thread(()->Outbox.flush(this,c),"nudge-outbox").start();}
    void showAudioControls(){Button rec=b("START RECORDING");rec.setOnClickListener(v->{if(!CapabilityProbe.microphone(this)){Toast.makeText(this,"Microphone permission is not granted",Toast.LENGTH_LONG).show();return;}try{audioFile=new File(getFilesDir(),"reply-"+System.currentTimeMillis()+".m4a");recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setOutputFile(audioFile.getAbsolutePath());recorder.prepare();recorder.start();rec.setText("RECORDING…");rec.setEnabled(false);}catch(Exception e){Toast.makeText(this,"Recording failed",Toast.LENGTH_LONG).show();}});root.addView(rec);Button save=b("STOP RECORDING & SEND");save.setOnClickListener(v->finishAudio(true));root.addView(save);}
    void finishAudio(boolean send){if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;}if(send&&audioFile!=null&&audioFile.length()>0){String eventId="ev-"+UUID.randomUUID();Outbox.enqueueAudio(this,eventId,spec.interactionId(),audioFile);WorkerClient c=client();if(c!=null)new Thread(()->Outbox.flush(this,c),"nudge-audio-outbox").start();}finish();}
    @Override protected void onDestroy(){if(timer!=null)timer.cancel();if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;}super.onDestroy();}
}
