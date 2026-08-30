package science.transductive.nudge;
import android.content.*;
import science.transductive.nudge.core.Protocol;
public final class NudgeAlarmReceiver extends BroadcastReceiver { @Override public void onReceive(Context c,Intent i){try{AttentionController.post(c,Protocol.parseInteraction(i.getStringExtra("interactionJson")));}catch(Exception ignored){}} }
