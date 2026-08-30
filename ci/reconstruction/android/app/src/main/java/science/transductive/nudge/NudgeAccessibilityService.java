package science.transductive.nudge;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
public final class NudgeAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e){ /* capability hook; no autonomous UI actuation in v1 */ }
    @Override public void onInterrupt(){}
}
