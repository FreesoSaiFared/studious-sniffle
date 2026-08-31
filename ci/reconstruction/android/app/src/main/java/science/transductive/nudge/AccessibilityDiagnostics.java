package science.transductive.nudge;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

public final class AccessibilityDiagnostics {
    private AccessibilityDiagnostics(){}

    public static String treeProbe(){
        NudgeAccessibilityService s=NudgeAccessibilityService.current();
        if(s==null)return "TREE_PROBE NOT_APPLIED accessibility_service_not_connected";
        NudgeAccessibilityService.Snapshot snap=s.snapshot(160);
        if(snap==null)return "TREE_PROBE UNKNOWN_OUTCOME no_active_root";
        return "TREE_PROBE APPLIED package="+snap.model.packageName+
            " nodes="+snap.model.nodes.size()+" generation="+snap.model.generation+
            " screen="+snap.model.screenWidth+"x"+snap.model.screenHeight;
    }

    public static String screenshotProbe(){
        NudgeAccessibilityService s=NudgeAccessibilityService.current();
        if(s==null)return "SCREENSHOT_PROBE NOT_APPLIED accessibility_service_not_connected";
        Bitmap b=s.screenshot(-1,5000);
        if(b==null)return "SCREENSHOT_PROBE UNKNOWN_OUTCOME capture_failed_or_timeout";
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!b.compress(Bitmap.CompressFormat.PNG,100,out))return "SCREENSHOT_PROBE UNKNOWN_OUTCOME png_compress_failed";
            byte[] data=out.toByteArray();
            return "SCREENSHOT_PROBE APPLIED width="+b.getWidth()+" height="+b.getHeight()+
                " bytes="+data.length+" sha256="+sha256(data);
        }finally{b.recycle();}
    }

    private static String sha256(byte[] data){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder x=new StringBuilder();for(byte v:d)x.append(String.format("%02x",v&255));return x.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
