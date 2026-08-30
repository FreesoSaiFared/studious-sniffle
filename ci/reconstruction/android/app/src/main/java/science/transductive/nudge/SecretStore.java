package science.transductive.nudge;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
    private static final String ALIAS="nudge-controller-token-v1", PREF="controller_secrets", KEY="token";
    private SecretStore(){}
    private static SecretKey key() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    public static void setToken(Context c,String token){try{Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.ENCRYPT_MODE,key());byte[] ct=x.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));String v=Base64.encodeToString(x.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(ct,Base64.NO_WRAP);c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,v).apply();}catch(Exception e){throw new IllegalStateException("Keystore write failed",e);}}
    public static String getToken(Context c){String v=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"");if(v.trim().isEmpty())return "";try{String[]p=v.split(":",2);Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));return new String(x.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){return "";}}
    public static boolean hasToken(Context c){return !c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"").trim().isEmpty();}
}
