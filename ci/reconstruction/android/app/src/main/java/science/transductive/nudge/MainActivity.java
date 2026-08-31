package science.transductive.nudge;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private LinearLayout root; private TextView status,automationResult; private EditText endpoint,token,automationPrompt,targetPackage;
    private int revealOrder=0;
    @Override public void onCreate(Bundle b){super.onCreate(b);build();}
    @Override public void onResume(){super.onResume();if(status!=null)status.setText(CapabilityProbe.summary(this));}

    private Button button(String t,View.OnClickListener l){
        Button b=new Button(this);b.setText(t);b.setOnClickListener(l);b.setAllCaps(false);PremiumUi.button(b,false);return b;
    }
    private void add(View v){root.addView(v);final int order=revealOrder++;v.post(()->PremiumUi.reveal(v,order));}
    private void field(EditText e){e.setTextColor(Color.WHITE);e.setHintTextColor(0xff8f94a3);e.setPadding(18,14,18,14);}

    private void build(){
        PremiumUi.window(this,false);
        ScrollView sv=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,48,32,48);root.setBackgroundColor(0xff101218);sv.addView(root);
        TextView h=new TextView(this);h.setText("NUDGE RUNTIME");h.setTextSize(30);h.setTypeface(Typeface.DEFAULT_BOLD);PremiumUi.heading(h);add(h);
        TextView sub=new TextView(this);sub.setText("Capability-first behavioral support and user-authorized Android automation. STOP/SNOOZE remains available.");sub.setTextSize(16);sub.setPadding(0,12,0,24);PremiumUi.body(sub);add(sub);
        status=new TextView(this);status.setTextSize(15);PremiumUi.body(status);add(status);
        SharedPreferences p=getSharedPreferences("controller",MODE_PRIVATE);

        endpoint=new EditText(this);endpoint.setHint("https://your-worker.example");endpoint.setText(p.getString("endpoint",""));field(endpoint);add(endpoint);
        token=new EditText(this);token.setHint("Controller bearer token (local device only)");token.setSingleLine(true);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);if(SecretStore.hasToken(this))token.setHint("Controller token saved in Android Keystore — enter only to replace");field(token);add(token);
        add(button("Save controller",v->{p.edit().putString("endpoint",endpoint.getText().toString().trim()).apply();if(!token.getText().toString().trim().isEmpty())SecretStore.setToken(this,token.getText().toString());token.setText("");Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();}));
        add(button("Start AI link",v->{p.edit().putString("endpoint",endpoint.getText().toString().trim()).apply();if(!token.getText().toString().trim().isEmpty())SecretStore.setToken(this,token.getText().toString());token.setText("");Intent i=new Intent(this,ControllerLinkService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}));
        add(button("Stop AI link",v->stopService(new Intent(this,ControllerLinkService.class))));
        if(Build.VERSION.SDK_INT>=33)add(button("Notification permission",v->requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},8)));

        CheckBox normalize=new CheckBox(this);normalize.setText("Urgent nudges may restore audible ringer when Notification Policy Access is granted");normalize.setTextColor(0xffe7e8ee);normalize.setChecked(p.getBoolean("normalizeRinger",true));normalize.setOnCheckedChangeListener((b,checked)->p.edit().putBoolean("normalizeRinger",checked).apply());add(normalize);
        add(button("Notification policy access",v->startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        if(Build.VERSION.SDK_INT>=31)add(button("Exact alarm access",v->startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())))));
        if(Build.VERSION.SDK_INT>=34)add(button("Full-screen intent access",v->startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName())))));
        add(button("Accessibility settings",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        add(button("Microphone permission",v->{if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{"android.permission.RECORD_AUDIO"},7);}));

        TextView autoHead=new TextView(this);autoHead.setText("CHATGPT APP AUTOMATION");autoHead.setTextSize(20);autoHead.setTypeface(Typeface.DEFAULT_BOLD);autoHead.setPadding(0,32,0,8);PremiumUi.heading(autoHead);add(autoHead);
        targetPackage=new EditText(this);targetPackage.setHint("Target Android package");targetPackage.setSingleLine(true);targetPackage.setText(p.getString("automationTarget","com.openai.chatgpt"));field(targetPackage);add(targetPackage);
        automationPrompt=new EditText(this);automationPrompt.setHint("Prompt to send through the ChatGPT Android app");automationPrompt.setMinLines(3);automationPrompt.setText(p.getString("automationPrompt","Reply with exactly: ACCESSIBILITY AUTOMATION WORKS"));field(automationPrompt);add(automationPrompt);
        automationResult=new TextView(this);automationResult.setText("No automation receipt yet.");automationResult.setTextSize(14);automationResult.setPadding(0,12,0,12);PremiumUi.body(automationResult);add(automationResult);
        add(button("Run ChatGPT automation probe",v->runAutomationProbe()));

        add(button("Run local surprise-demo",v->{Intent i=new Intent(this,InterruptionActivity.class);i.putExtra("prompt","DO NOT THINK. Answer within two seconds. Ready to see it?");i.putExtra("mode","choice");startActivity(i);}));
        add(button("Run local audio-answer demo",v->{Intent i=new Intent(this,InterruptionActivity.class);i.putExtra("prompt","Answer out loud now. Ready?");i.putExtra("mode","audio");startActivity(i);}));
        setContentView(sv);
    }

    private void runAutomationProbe(){
        final String pkg=targetPackage.getText().toString().trim(),prompt=automationPrompt.getText().toString();
        getSharedPreferences("controller",MODE_PRIVATE).edit().putString("automationTarget",pkg).putString("automationPrompt",prompt).apply();
        automationResult.setText("Running package-fenced automation…");
        new Thread(()->{
            ChatGptAutomationEngine.Result r=ChatGptAutomationEngine.run(getApplicationContext(),"local-"+System.currentTimeMillis(),pkg,prompt,90_000);
            runOnUiThread(()->automationResult.setText(r.status+"\n"+r.evidence+"\nSHA-256 "+r.responseSha256+"\n\n"+r.response));
        },"chatgpt-automation-probe").start();
    }
}
