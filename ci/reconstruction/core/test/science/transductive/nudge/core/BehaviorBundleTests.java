package science.transductive.nudge.core;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

public final class BehaviorBundleTests {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void bad(Runnable r,String n){try{r.run();throw new AssertionError(n);}catch(IllegalArgumentException e){pass++;}}
    public static void main(String[]args)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair kp=g.generateKeyPair();
        long now=1700000000000L;
        String payload="{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"CHATGPT_PROMPT\",\"text\":\"hello\"},{\"op\":\"DELAY\",\"delayMs\":10},{\"op\":\"EMIT\",\"key\":\"answer\"}]}";
        String bundle=SignedBehaviorBundle.createForTestOrController("b1",now-1000,now+60000,"controller",payload,kp.getPrivate());
        Map<String,byte[]>keys=new HashMap<String,byte[]>();keys.put("controller",kp.getPublic().getEncoded());
        SignedBehaviorBundle.Verified v=SignedBehaviorBundle.verify(bundle,now,keys);
        ok(v.bundleId.equals("b1"),"valid signed bundle");
        ok(BehaviorProgram.parse(v.payloadJson).steps.size()==3,"program parse");
        bad(()->SignedBehaviorBundle.verify(bundle,now,Collections.<String,byte[]>emptyMap()),"untrusted signer");
        bad(()->SignedBehaviorBundle.verify(bundle,now+120000,keys),"expired");
        String tampered=bundle.replace("\\\"hello\\\"","\\\"bye\\\"");
        bad(()->SignedBehaviorBundle.verify(tampered,now,keys),"tampered payload");
        bad(()->BehaviorProgram.parse("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"SHELL\"}]}"),"unsupported op");
        bad(()->BehaviorProgram.parse("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"DELAY\",\"delayMs\":30001}]}"),"delay bound");
        ok(SignedBehaviorBundle.decodeP256PublicKey(kp.getPublic().getEncoded()).getAlgorithm().equals("EC"),"p256 decode");
        bad(()->BehaviorProgram.parse("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"NUDGE\",\"text\":\"now\"},{\"op\":\"EMIT\",\"key\":\"x\"}]}"),"nudge must be final");
        Map<String,Double> features=new LinkedHashMap<String,Double>();features.put("blink_rate",0.42);
        Map<String,Double> hypotheses=new LinkedHashMap<String,Double>();hypotheses.put("tension_cue",0.35);
        AffectObservation observation=new AffectObservation("mediapipe","face-landmarker-x","user-consented-camera",now,0.9,features,hypotheses);
        ok(observation.json().contains("UNCERTAIN_OBSERVATION_NOT_GROUND_TRUTH"),"affect authority");
        System.out.println("BEHAVIOR_BUNDLE_TESTS_PASS="+pass);
    }
}
