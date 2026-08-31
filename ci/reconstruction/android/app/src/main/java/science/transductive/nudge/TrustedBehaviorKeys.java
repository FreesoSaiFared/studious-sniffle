package science.transductive.nudge;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import science.transductive.nudge.core.SignedBehaviorBundle;

public final class TrustedBehaviorKeys {
    private static final String PREF="trusted_behavior_keys";
    private TrustedBehaviorKeys(){}

    public static void put(Context c,String keyId,String base64X509){
        if(keyId==null||!keyId.matches("[A-Za-z0-9._:-]{1,160}"))throw new IllegalArgumentException("bad key id");
        byte[] raw;
        try{raw=Base64.getDecoder().decode(base64X509.trim());}catch(Exception e){throw new IllegalArgumentException("bad public key base64");}
        SignedBehaviorBundle.decodeP256PublicKey(raw);
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(keyId,Base64.getEncoder().encodeToString(raw)).apply();
    }
    public static Map<String,byte[]> all(Context c){
        Map<String,byte[]> out=new HashMap<String,byte[]>();
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        for(Map.Entry<String,?>e:p.getAll().entrySet())if(e.getValue() instanceof String){
            try{out.put(e.getKey(),Base64.getDecoder().decode((String)e.getValue()));}catch(Exception ignored){}
        }
        return out;
    }
    public static int count(Context c){return all(c).size();}
    public static void remove(Context c,String keyId){
        if(keyId!=null)c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(keyId).apply();
    }
}
