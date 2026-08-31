package science.transductive.nudge.core;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

public final class SignedBehaviorAcceptance {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void rejected(Runnable r,String n){
        try{r.run();throw new AssertionError(n);}catch(IllegalArgumentException expected){pass++;}
    }
    public static void main(String[]args)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp=g.generateKeyPair();
        long now=1_700_000_000_000L;
        String payload="{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":["
            +"{\"op\":\"CHATGPT_PROMPT\",\"targetPackage\":\"com.openai.chatgpt\",\"text\":\"synthetic prompt\"},"
            +"{\"op\":\"EMIT\",\"key\":\"answer\"},"
            +"{\"op\":\"DELAY\",\"delayMs\":1}]}";
        String bundle=SignedBehaviorBundle.createForTestOrController(
            "fixture-1",now-1000,now+60000,"fixture-key",payload,kp.getPrivate());
        Map<String,byte[]> trusted=new HashMap<String,byte[]>();
        trusted.put("fixture-key",kp.getPublic().getEncoded());

        SignedBehaviorBundle.Verified verified=SignedBehaviorBundle.verify(bundle,now,trusted);
        ok("fixture-1".equals(verified.bundleId),"real signed bundle verifies");

        BehaviorProgram program=BehaviorProgram.parse(verified.payloadJson);
        ok(program.steps.size()==3,"bounded program parses");

        String last="";
        LinkedHashMap<String,String> emitted=new LinkedHashMap<String,String>();
        StringBuilder trace=new StringBuilder();
        for(BehaviorProgram.Step step:program.steps){
            if(trace.length()>0)trace.append(">");
            trace.append(step.op.name());
            switch(step.op){
                case CHATGPT_PROMPT:
                    last="synthetic-response";
                    break;
                case EMIT:
                    emitted.put(step.key,last);
                    break;
                case DELAY:
                    if(step.delayMs<0||step.delayMs>30000)throw new AssertionError("delay escaped bound");
                    break;
                case NUDGE:
                    throw new AssertionError("fixture did not request human interaction");
            }
        }
        ok("CHATGPT_PROMPT>EMIT>DELAY".equals(trace.toString()),"synthetic execution order");
        ok("synthetic-response".equals(emitted.get("answer")),"synthetic emit carries response");

        String tampered=bundle.replace("synthetic prompt","tampered prompt");
        rejected(()->SignedBehaviorBundle.verify(tampered,now,trusted),"tampering rejected");
        rejected(()->SignedBehaviorBundle.verify(bundle,now+120000,trusted),"expiry rejected");
        rejected(()->SignedBehaviorBundle.verify(bundle,now,Collections.<String,byte[]>emptyMap()),"untrusted signer rejected");

        System.out.println("SIGNED_BEHAVIOR_ACCEPTANCE_PASS="+pass);
    }
}
