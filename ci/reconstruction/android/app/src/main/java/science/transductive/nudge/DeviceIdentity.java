package science.transductive.nudge;
import android.content.*;
import java.util.UUID;
public final class DeviceIdentity {private DeviceIdentity(){} public static synchronized String get(Context c){SharedPreferences p=c.getSharedPreferences("controller",Context.MODE_PRIVATE);String v=p.getString("deviceId","");if(v.trim().isEmpty()){v="android-"+UUID.randomUUID();p.edit().putString("deviceId",v).apply();}return v;}}
