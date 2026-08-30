package science.transductive.nudge;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

public final class CapabilityProbe {
    private CapabilityProbe() {}
    public static boolean notifications(Context c){ return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED; }
    public static boolean exactAlarm(Context c){ AlarmManager a=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE); return Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms(); }
    public static boolean fullScreen(Context c){ NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE); return Build.VERSION.SDK_INT < 34 || n.canUseFullScreenIntent(); }
    public static boolean policy(Context c){ return ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted(); }
    public static boolean microphone(Context c){ return c.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED; }
    public static boolean accessibility(Context c){ String enabled=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); return enabled!=null && enabled.contains(c.getPackageName()); }
    public static String summary(Context c){return "notifications="+notifications(c)+"\nfullScreenIntent="+fullScreen(c)+"\nexactAlarm="+exactAlarm(c)+"\nnotificationPolicy="+policy(c)+"\nmicrophone="+microphone(c)+"\naccessibility="+accessibility(c);}
}
