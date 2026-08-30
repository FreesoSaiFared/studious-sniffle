package science.transductive.nudge;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private LinearLayout root; private TextView status; private EditText endpoint,token;
    @Override public void onCreate(Bundle b){super.onCreate(b); build();}
    @Override public void onResume(){super.onResume(); if(status!=null)status.setText(CapabilityProbe.summary(this));}
    private Button button(String t, View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);b.setAllCaps(false);return b;}
    private void build(){
        ScrollView sv=new ScrollView(this); root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,48,32,48);sv.addView(root);
        TextView h=new TextView(this);h.setText("NUDGE RUNTIME");h.setTextSize(28);h.setTypeface(Typeface.DEFAULT_BOLD);root.addView(h);
        TextView sub=new TextView(this);sub.setText("Capability-first behavioral stimulus client. STOP/SNOOZE remains available. Starting the AI link creates a persistent visible status notification.");sub.setTextSize(16);sub.setPadding(0,12,0,24);root.addView(sub);
        status=new TextView(this);status.setTextSize(16);root.addView(status);
        SharedPreferences p=getSharedPreferences("controller",MODE_PRIVATE);
        endpoint=new EditText(this);endpoint.setHint("https://your-worker.example");endpoint.setText(p.getString("endpoint",""));root.addView(endpoint);
        token=new EditText(this);token.setHint("Controller bearer token (local device only)");token.setSingleLine(true);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);if(SecretStore.hasToken(this))token.setHint("Controller token saved in Android Keystore — enter only to replace");root.addView(token);
        root.addView(button("Save controller",v->{p.edit().putString("endpoint",endpoint.getText().toString().trim()).apply();if(!token.getText().toString().trim().isEmpty())SecretStore.setToken(this,token.getText().toString());token.setText("");Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();}));
        root.addView(button("Start AI link",v->{p.edit().putString("endpoint",endpoint.getText().toString().trim()).apply();if(!token.getText().toString().trim().isEmpty())SecretStore.setToken(this,token.getText().toString());token.setText("");Intent i=new Intent(this,ControllerLinkService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}));
        root.addView(button("Stop AI link",v->stopService(new Intent(this,ControllerLinkService.class))));
        if(Build.VERSION.SDK_INT>=33)root.addView(button("Notification permission",v->requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},8)));
        CheckBox normalize=new CheckBox(this);normalize.setText("Urgent nudges may restore audible ringer when Notification Policy Access is granted");normalize.setChecked(p.getBoolean("normalizeRinger",true));normalize.setOnCheckedChangeListener((b,checked)->p.edit().putBoolean("normalizeRinger",checked).apply());root.addView(normalize);
        root.addView(button("Notification policy access",v->startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        if(Build.VERSION.SDK_INT>=31)root.addView(button("Exact alarm access",v->startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+getPackageName())))));
        if(Build.VERSION.SDK_INT>=34)root.addView(button("Full-screen intent access",v->startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:"+getPackageName())))));
        root.addView(button("Accessibility settings",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("Microphone permission",v->{if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{"android.permission.RECORD_AUDIO"},7);}));
        root.addView(button("Run local surprise-demo",v->{Intent i=new Intent(this,InterruptionActivity.class);i.putExtra("prompt","DO NOT THINK. Answer within two seconds. Ready to see it?");i.putExtra("mode","choice");startActivity(i);}));
        root.addView(button("Run local audio-answer demo",v->{Intent i=new Intent(this,InterruptionActivity.class);i.putExtra("prompt","Answer out loud now. Ready?");i.putExtra("mode","audio");startActivity(i);}));
        setContentView(sv);
    }
}
