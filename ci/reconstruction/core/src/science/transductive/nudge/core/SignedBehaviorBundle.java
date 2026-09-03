package science.transductive.nudge.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.ECPublicKey;
import java.util.*;
import java.util.regex.Pattern;

public final class SignedBehaviorBundle {
    public static final String SCHEMA="SIGNED_BEHAVIOR_BUNDLE/1";
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9._:-]{1,160}");
    private SignedBehaviorBundle(){}

    public static final class Verified {
        public final String bundleId,signerKeyId,payloadJson,payloadSha256;
        public final long issuedAtMs,expiresAtMs;
        Verified(String b,String k,String p,String h,long i,long e){bundleId=b;signerKeyId=k;payloadJson=p;payloadSha256=h;issuedAtMs=i;expiresAtMs=e;}
    }

    @SuppressWarnings("unchecked")
    public static Verified verify(String json,long nowMs,Map<String,byte[]> trustedKeys){
        if(json==null||json.length()>128000)throw new IllegalArgumentException("bundle too large");
        Object root=MiniJson.parse(json);if(!(root instanceof Map))throw new IllegalArgumentException("bundle root");
        Map<String,Object>m=(Map<String,Object>)root;
        String schema=req(m,"schema"),id=req(m,"bundleId"),keyId=req(m,"signerKeyId");
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("unsupported bundle schema");
        if(!ID.matcher(id).matches()||!ID.matcher(keyId).matches())throw new IllegalArgumentException("bad bundle id");
        long issued=num(m,"issuedAtMs"),expires=num(m,"expiresAtMs");
        if(issued>nowMs+300000L)throw new IllegalArgumentException("bundle issued in future");
        if(expires<=nowMs)throw new IllegalArgumentException("bundle expired");
        if(expires<issued||expires-issued>604800000L)throw new IllegalArgumentException("bad bundle lifetime");
        String payload=req(m,"payload"),declared=req(m,"payloadSha256"),sig=req(m,"signatureBase64");
        if(payload.length()>64000)throw new IllegalArgumentException("payload too large");
        String actual=sha256(payload);if(!actual.equals(declared))throw new IllegalArgumentException("payload hash mismatch");
        byte[] key=trustedKeys==null?null:trustedKeys.get(keyId);if(key==null)throw new IllegalArgumentException("untrusted signer");
        if(!verifyP256(key,signatureInput(id,issued,expires,keyId,actual,payload),decode64(sig)))
            throw new IllegalArgumentException("signature invalid");
        return new Verified(id,keyId,payload,actual,issued,expires);
    }

    public static String createForTestOrController(String bundleId,long issued,long expires,String keyId,String payload,PrivateKey privateKey){
        String hash=sha256(payload);byte[] message=signatureInput(bundleId,issued,expires,keyId,hash,payload);
        try{
            Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(privateKey);s.update(message);
            Map<String,Object>m=new LinkedHashMap<String,Object>();m.put("schema",SCHEMA);m.put("bundleId",bundleId);m.put("issuedAtMs",issued);m.put("expiresAtMs",expires);
            m.put("signerKeyId",keyId);m.put("payloadSha256",hash);m.put("payload",payload);m.put("signatureBase64",Base64.getEncoder().encodeToString(s.sign()));
            return MiniJson.stringify(m);
        }catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }

    public static PublicKey decodeP256PublicKey(byte[] x509){
        try{
            PublicKey k=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
            if(!"EC".equalsIgnoreCase(k.getAlgorithm())||!(k instanceof ECPublicKey)||!isP256((ECPublicKey)k))throw new IllegalArgumentException("not P-256 key");
            return k;
        }catch(GeneralSecurityException e){throw new IllegalArgumentException("bad P-256 public key",e);}
    }

    private static boolean isP256(ECPublicKey k)throws GeneralSecurityException{
        AlgorithmParameters p=AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=p.getParameterSpec(ECParameterSpec.class),actual=k.getParams();
        return actual!=null&&actual.getCofactor()==expected.getCofactor()&&
            actual.getOrder().equals(expected.getOrder())&&
            actual.getGenerator().equals(expected.getGenerator())&&
            actual.getCurve().equals(expected.getCurve());
    }
    private static boolean verifyP256(byte[] x509,byte[] message,byte[] signature){
        try{Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(decodeP256PublicKey(x509));s.update(message);return s.verify(signature);}
        catch(GeneralSecurityException|IllegalArgumentException e){return false;}
    }
    private static byte[] signatureInput(String id,long issued,long expires,String keyId,String hash,String payload){
        return (SCHEMA+"\n"+id+"\n"+issued+"\n"+expires+"\n"+keyId+"\n"+hash+"\n"+payload).getBytes(StandardCharsets.UTF_8);
    }
    public static String sha256(String s){
        try{byte[]d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x&255));return b.toString();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static byte[] decode64(String v){try{return Base64.getDecoder().decode(v);}catch(IllegalArgumentException e){throw new IllegalArgumentException("bad signature base64");}}
    private static String req(Map<String,Object>m,String k){Object v=m.get(k);if(!(v instanceof String)||((String)v).trim().isEmpty())throw new IllegalArgumentException("missing "+k);return(String)v;}
    private static long num(Map<String,Object>m,String k){Object v=m.get(k);if(!(v instanceof Number))throw new IllegalArgumentException("missing "+k);return((Number)v).longValue();}
}
