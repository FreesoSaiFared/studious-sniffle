package science.transductive.nudge.core;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

public final class SignedModuleManifestTests {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void bad(Runnable r,String n){try{r.run();throw new AssertionError(n);}catch(IllegalArgumentException expected){pass++;}}
    public static void main(String[]args)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair kp=g.generateKeyPair();
        long now=1_700_000_000_000L;byte[]dex=new byte[]{100,101,120,10};
        String entry="science.transductive.nudge.modules.FixtureModule";
        String json=SignedModuleManifest.createForTestOrController("m1",1,entry,dex,"controller",now-1000,now+60000,kp.getPrivate());
        Map<String,byte[]>keys=new HashMap<String,byte[]>();keys.put("controller",kp.getPublic().getEncoded());
        SignedModuleManifest.Verified v=SignedModuleManifest.verify(json,now,keys,dex);
        ok(v.moduleId.equals("m1")&&v.version==1,"valid module");
        ok(v.entryClass.equals(entry),"entry class");
        bad(()->SignedModuleManifest.verify(json,now,Collections.<String,byte[]>emptyMap(),dex),"untrusted signer");
        bad(()->SignedModuleManifest.verify(json,now+120000,keys,dex),"expired");
        bad(()->SignedModuleManifest.verify(json,now,keys,new byte[]{1,2,3}),"dex tamper");
        String badEntry=SignedModuleManifest.createForTestOrController("m2",1,"evil.Module",dex,"controller",now-1000,now+60000,kp.getPrivate());
        bad(()->SignedModuleManifest.verify(badEntry,now,keys,dex),"entry namespace");
        String tampered=json.replace("\"version\":1","\"version\":2");
        bad(()->SignedModuleManifest.verify(tampered,now,keys,dex),"manifest tamper");
        for(String curve:new String[]{"secp384r1","secp521r1"}){
            KeyPairGenerator otherGen=KeyPairGenerator.getInstance("EC");
            otherGen.initialize(new ECGenParameterSpec(curve));
            KeyPair other=otherGen.generateKeyPair();
            Map<String,byte[]> foreignTrust=new HashMap<String,byte[]>();
            foreignTrust.put("controller",other.getPublic().getEncoded());
            String foreign=SignedModuleManifest.createForTestOrController("curve-"+curve,1,entry,dex,"controller",now-1000,now+60000,other.getPrivate());
            bad(()->SignedModuleManifest.verify(foreign,now,foreignTrust,dex),curve+" signer rejected");
        }
        System.out.println("SIGNED_MODULE_TESTS_PASS="+pass);
    }
}
