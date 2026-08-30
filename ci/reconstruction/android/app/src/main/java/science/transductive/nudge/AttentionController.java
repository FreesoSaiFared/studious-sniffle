package science.transductive.nudge;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.media.RingtoneManager;
import science.transductive.nudge.core.Models.InteractionSpec;
import science.transductive.nudge.core.Protocol;

public final class AttentionController {
    public static final String CH="nudge_urgent";
    private AttentionController(){}
    public static void ensureChannel(Context c){
        if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);NotificationChannel ch=new NotificationChannel(CH,"Urgent nudges",NotificationManager.IMPORTANCE_HIGH);ch.enableVibration(true);ch.setVibrationPattern(new long[]{0,250,120,250,120,500});ch.setDescription("User-authorized interruption prompts");Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();ch.setSound(sound,aa);if(nm.isNotificationPolicyAccessGranted())ch.setBypassDnd(true);nm.createNotificationChannel(ch);}
    }
    public static void maximizeAttention(Context c){
        boolean allowed=c.getSharedPreferences("controller",Context.MODE_PRIVATE).getBoolean("normalizeRinger",true);if(!allowed)return;NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(!nm.isNotificationPolicyAccessGranted())return;AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);try{if(!am.isVolumeFixed())am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);}catch(SecurityException ignored){}
    }
    public static void post(Context c,InteractionSpec s){
        maximizeAttention(c);ensureChannel(c);Intent open=new Intent(c,InterruptionActivity.class);open.putExtra("interactionJson",Protocol.stringifyInteraction(s));
        PendingIntent pi=PendingIntent.getActivity(c,s.interactionId().hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CH):new Notification.Builder(c);b.setContentTitle(s.title().trim().isEmpty()?"Nudge":s.title()).setContentText(s.readyGate()?s.readyPrompt():s.prompt()).setSmallIcon(android.R.drawable.ic_dialog_info).setPriority(Notification.PRIORITY_MAX).setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(pi);if(s.requestFullScreen()&&CapabilityProbe.fullScreen(c))b.setFullScreenIntent(pi,true);((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(1001,b.build());
    }
}
