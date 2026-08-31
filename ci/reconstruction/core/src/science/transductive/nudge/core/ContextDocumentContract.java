package science.transductive.nudge.core;

import java.security.*;
import java.util.*;

public final class ContextDocumentContract {
    public static final long MAX_BYTES=2L*1024L*1024L;
    private ContextDocumentContract(){}

    public static Map<String,Object> metadata(String name,String mimeType,String sourceUri,byte[] data,long importedAtMs){
        if(data==null)throw new IllegalArgumentException("missing data");
        if(data.length>MAX_BYTES)throw new IllegalArgumentException("context document exceeds 2 MiB");
        String n=req(name,"name"),m=req(mimeType,"mimeType"),u=req(sourceUri,"sourceUri");
        LinkedHashMap<String,Object> out=new LinkedHashMap<String,Object>();
        out.put("schema","CONSENTED_CONTEXT_DOCUMENT/1");
        out.put("sha256",sha256(data));
        out.put("name",n);
        out.put("mimeType",m);
        out.put("bytes",data.length);
        out.put("sourceUri",u);
        out.put("importedAtMs",importedAtMs);
        out.put("authority","USER_SELECTED_SOURCE");
        return Collections.unmodifiableMap(out);
    }

    public static String sha256(byte[] data){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b=new StringBuilder();
            for(byte x:d)b.append(String.format("%02x",x&255));
            return b.toString();
        }catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }

    private static String req(String v,String name){
        if(v==null||v.trim().isEmpty())throw new IllegalArgumentException("missing "+name);
        return v;
    }
}
