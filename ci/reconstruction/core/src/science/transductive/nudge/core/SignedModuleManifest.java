package science.transductive.nudge.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;

public final class SignedModuleManifest {
    public static final String SCHEMA="SIGNED_DEX_MODULE/1";
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9._:-]{1,160}");
    private static final Pattern ENTRY=Pattern.compile("science\\.transductive\\.nudge\\.modules\\.[A-Za-z_$][A-Za-z0-9_.$]{0,220}");

    public static final class Verified {
        public final String moduleId,entryClass,dexSha256,signerKeyId;
        public final long version,issuedAtMs,expiresAtMs;
        Verified(String id,long v,String cls,String hash,String key,long issued,long expires){
            moduleId=id;version=v;entryClass=cls;dexSha256=hash;signerKeyId=key;issuedAtMs=issued;expiresAtMs=expires;
        }
    }

    private SignedModuleManifest(){}

    @SuppressWarnings("unchecked")
    public static Verified verify(String json,long nowMs,Map<String,byte[]>trustedKeys,byte[]dex){
        if(json==null||json.length()>32000)throw new IllegalArgumentException("manifest too large");
        Object root=MiniJson.parse(json);if(!(root instanceof Map))throw new IllegalArgumentException("manifest root");
        Map<String,Object>m=(Map<String,Object>)root;
        if(!SCHEMA.equals(req(m,"schema")))throw new IllegalArgumentException("unsupported module schema");
        String id=req(m,"moduleId"),entry=req(m,"entryClass"),hash=req(m,"dexSha256"),keyId=req(m,"signerKeyId");
        if(!ID.matcher(id).matches()||!ID.matcher(keyId).matches())throw new IllegalArgumentException("bad module/key id");
        if(!ENTRY.matcher(entry).matches())throw new IllegalArgumentException("entry class outside module namespace");
        long version=num(m,"version"),issued=num(m,"issuedAtMs"),expires=num(m,"expiresAtMs");
        if(version<1)throw new IllegalArgumentException("bad module version");
        if(issued>nowMs+300000L)throw new IllegalArgumentException("module issued in future");
        if(expires<=nowMs)throw new IllegalArgumentException("module expired");
        if(expires<issued||expires-issued>604800000L)throw new IllegalArgumentException("bad module lifetime");
        String actual=sha256(dex);
        if(!actual.equals(hash))throw new IllegalArgumentException("dex hash mismatch");
        byte[]key=trustedKeys==null?null:trustedKeys.get(keyId);if(key==null)throw new IllegalArgumentException("untrusted signer");
        byte[]sig=decode64(req(m,"signatureBase64"));
        if(!verifyP256(key,signatureInput(id,version,entry,hash,keyId,issued,expires),sig))
            throw new IllegalArgumentException("module signature invalid");
        return new Verified(id,version,entry,hash,keyId,issued,expires);
    }

    public static String createForTestOrController(String id,long version,String entry,byte[]dex,String keyId,long issued,long expires,PrivateKey privateKey){
        String hash=sha256(dex);
        try{
            Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(privateKey);
            s.update(signatureInput(id,version,entry,hash,keyId,issued,expires));
            Map<String,Object>m=new LinkedHashMap<String,Object>();
            m.put("schema",SCHEMA);m.put("moduleId",id);m.put("version",version);m.put("entryClass",entry);
            m.put("dexSha256",hash);m.put("signerKeyId",keyId);m.put("issuedAtMs",issued);m.put("expiresAtMs",expires);
            m.put("signatureBase64",Base64.getEncoder().encodeToString(s.sign()));
            return MiniJson.stringify(m);
        }catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }

    public static String sha256(byte[]data){
        if(data==null)throw new IllegalArgumentException("missing dex");
        try{byte[]d=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x&255));return b.toString();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static byte[]signatureInput(String id,long version,String entry,String hash,String key,long issued,long expires){
        return (SCHEMA+"\n"+id+"\n"+version+"\n"+entry+"\n"+hash+"\n"+key+"\n"+issued+"\n"+expires).getBytes(StandardCharsets.UTF_8);
    }
    private static boolean verifyP256(byte[]x509,byte[]message,byte[]sig){
        try{
            PublicKey k=SignedBehaviorBundle.decodeP256PublicKey(x509);
            Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(k);s.update(message);return s.verify(sig);
        }catch(GeneralSecurityException e){return false;}
    }
    private static byte[]decode64(String v){try{return Base64.getDecoder().decode(v);}catch(IllegalArgumentException e){throw new IllegalArgumentException("bad signature base64");}}
    private static String req(Map<String,Object>m,String k){Object v=m.get(k);if(!(v instanceof String)||((String)v).trim().isEmpty())throw new IllegalArgumentException("missing "+k);return(String)v;}
    private static long num(Map<String,Object>m,String k){Object v=m.get(k);if(!(v instanceof Number))throw new IllegalArgumentException("missing "+k);return((Number)v).longValue();}
}
