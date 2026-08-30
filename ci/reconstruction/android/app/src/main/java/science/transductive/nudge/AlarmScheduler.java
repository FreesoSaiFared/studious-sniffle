package science.transductive.nudge;
import android.app.*;
import android.content.*;
import android.os.Build;
import science.transductive.nudge.core.Models.InteractionSpec;
import science.transductive.nudge.core.Protocol;

public final class AlarmScheduler {
    private AlarmScheduler(){}
    public static boolean schedule(Context c, InteractionSpec s){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        Intent i=new Intent(c,NudgeAlarmReceiver.class).putExtra("interactionJson",Protocol.stringifyInteraction(s));
        PendingIntent pi=PendingIntent.getBroadcast(c,s.interactionId().hashCode(),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long at=Math.max(System.currentTimeMillis(),s.triggerAtMs());
        if(s.requiresExactTiming() && CapabilityProbe.exactAlarm(c)){if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);else am.setExact(AlarmManager.RTC_WAKEUP,at,pi);return true;}
        if(Build.VERSION.SDK_INT>=23)am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);else am.set(AlarmManager.RTC_WAKEUP,at,pi);return false;
    }
}
