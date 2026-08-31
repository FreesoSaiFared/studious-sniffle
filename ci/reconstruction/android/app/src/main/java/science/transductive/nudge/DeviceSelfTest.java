package science.transductive.nudge;

import android.content.Context;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import science.transductive.nudge.core.SignedBehaviorBundle;

public final class DeviceSelfTest {
    private DeviceSelfTest(){}

    public static String signedBehaviorFixture(Context c){
        String keyId="device-fixture-"+System.currentTimeMillis();
        try{
            KeyPairGenerator g=KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp=g.generateKeyPair();
            TrustedBehaviorKeys.put(c,keyId,Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            long now=System.currentTimeMillis();
            String payload="{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"DELAY\",\"delayMs\":1},{\"op\":\"EMIT\",\"key\":\"fixture\"}]}";
            String bundle=SignedBehaviorBundle.createForTestOrController(
                "device-fixture-"+now,now-1000,now+60000,keyId,payload,kp.getPrivate());
            BehaviorRuntime.Result result=BehaviorRuntime.run(c,bundle);
            return "SIGNED_BEHAVIOR_DEVICE_PROBE "+result.status+" "+result.evidence+" outputs="+result.outputs;
        }catch(Throwable t){
            return "SIGNED_BEHAVIOR_DEVICE_PROBE NOT_APPLIED "+t.getClass().getSimpleName()+":"+String.valueOf(t.getMessage());
        }finally{
            TrustedBehaviorKeys.remove(c,keyId);
        }
    }
}
