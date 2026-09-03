package science.transductive.nudge.core;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static science.transductive.nudge.core.Models.*;

public final class VmStressTests {
    private static int pass=0;
    private static final Random R=new Random(0x5E1F2026L);
    private interface X { void run() throws Exception; }
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void bad(X x,String n){try{x.run();throw new AssertionError(n+" did not reject");}catch(IllegalArgumentException expected){pass++;}catch(Exception e){throw new RuntimeException(n,e);}}
    private static String randomString(int n){String c="abcXYZ09 _-\\\"\n\t";StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(c.charAt(R.nextInt(c.length())));return b.toString();}

    private static void jsonStress() throws Exception {
        for(int i=0;i<10000;i++){
            LinkedHashMap<String,Object>m=new LinkedHashMap<String,Object>();
            m.put("i",(long)i);m.put("b",(i&1)==0);m.put("s",randomString(R.nextInt(40)));
            m.put("a",Arrays.asList((long)(i%7),randomString(5),null,true));
            String a=MiniJson.stringify(m),b=MiniJson.stringify(MiniJson.parse(a));
            if(!a.equals(b))throw new AssertionError("json canonical mismatch "+i);
        }
        ok(true,"json 10000 canonical");
        bad(()->MiniJson.parse("{\"a\":1,\"a\":2}"),"duplicate key");
        bad(()->MiniJson.parse("\"line\nraw\""),"raw control");
        bad(()->MiniJson.parse("1."),"fraction digit");
    }

    private static void protocolStress() throws Exception {
        long now=1900000000000L;
        for(int i=0;i<5000;i++){
            Capture cap=Capture.values()[R.nextInt(Capture.values().length)];
            List<Choice>choices=new ArrayList<Choice>();
            if(cap==Capture.CHOICE){choices.add(new Choice("yes","YES"));choices.add(new Choice("no","NO"));}
            InteractionSpec s=new InteractionSpec(Protocol.VERSION,"stress-"+i,now-1000,now+R.nextInt(1000),now+60000,"T","READY",R.nextBoolean(),"prompt-"+i,Attention.values()[R.nextInt(3)],cap,choices,R.nextInt(120001),R.nextBoolean(),R.nextBoolean());
            InteractionSpec p=Protocol.parseInteraction(Protocol.stringifyInteraction(s));
            if(!s.equals(p))throw new AssertionError("protocol roundtrip "+i);
            Protocol.validateForNow(p,now,Collections.<String>emptySet());
        }
        ok(true,"protocol 5000");
        InteractionSpec audio=new InteractionSpec(Protocol.VERSION,"matrix",now-1,now,now+10000,"","R",false,"p",Attention.URGENT,Capture.AUDIO,Collections.<Choice>emptyList(),0,true,true);
        int cases=0;
        for(int bits=0;bits<128;bits++){
            CapabilitySnapshot c=new CapabilitySnapshot((bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0,(bits&32)!=0,(bits&64)!=0);
            Plan p=Protocol.plan(audio,c);
            if(p.canStartAudio()!=(c.microphone()&&c.userVisible()))throw new AssertionError("audio matrix "+bits);
            if(p.delivery()==Delivery.FULL_SCREEN&&(!c.notifications()||!c.fullScreenIntent()))throw new AssertionError("fullscreen matrix "+bits);
            cases++;
        }
        ok(cases==128,"capability matrix");
    }

    private static void replayStress() throws Exception {
        ReplayGuard g=new ReplayGuard(64);AtomicInteger accepted=new AtomicInteger();
        ExecutorService ex=Executors.newFixedThreadPool(16);List<Future<?>>fs=new ArrayList<Future<?>>();
        for(int i=0;i<64;i++)fs.add(ex.submit(()->{if(g.accept("same-id"))accepted.incrementAndGet();}));
        for(Future<?>f:fs)f.get();ex.shutdown();
        ok(accepted.get()==1,"replay concurrent once");
    }

    private static AutomationPlanner.Node node(int i,boolean edit,boolean click,boolean enable,boolean pw,boolean focus,int x,int y){
        String sem=R.nextBoolean()?"":"noise"+i;
        return new AutomationPlanner.Node(i,sem,"","android.widget."+(edit?"EditText":"Button"),sem,x,y,x+100,y+80,edit,click,enable,pw,focus);
    }
    private static void automationStress(){
        for(int k=0;k<5000;k++){
            ArrayList<AutomationPlanner.Node>ns=new ArrayList<AutomationPlanner.Node>();
            for(int i=0;i<20;i++)ns.add(node(i,R.nextInt(5)==0,R.nextBoolean(),R.nextBoolean(),R.nextInt(20)==0,R.nextInt(30)==0,R.nextInt(900),R.nextInt(2200)));
            AutomationPlanner.Snapshot s=new AutomationPlanner.Snapshot("com.openai.chatgpt",k,1080,2400,ns);
            int ci=AutomationPlanner.findComposer(s);
            if(ci>=0){
                AutomationPlanner.Node c=s.byIndex(ci);
                if(!(c.enabled&&c.editable&&!c.password))throw new AssertionError("composer invariant");
                int si=AutomationPlanner.findSend(s,c);
                if(si>=0){
                    AutomationPlanner.Node n=s.byIndex(si);String z=n.semantic();
                    if(!(n.enabled&&n.clickable&&!n.password)||z.contains("microphone")||z.contains("voice")||z.contains("attach")||z.contains("camera")||z.contains("stop")||z.contains("cancel"))throw new AssertionError("send invariant");
                }
            }
        }
        ok(true,"automation 5000");
    }

    private static void behaviorStress() throws Exception {
        StringBuilder p=new StringBuilder("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[");
        for(int i=0;i<32;i++){if(i>0)p.append(',');p.append("{\"op\":\"EMIT\",\"key\":\"k").append(i).append("\"}");}p.append("]}");
        ok(BehaviorProgram.parse(p.toString()).steps.size()==32,"32 steps");
        String too=p.toString().replace("]}",",{\"op\":\"EMIT\",\"key\":\"overflow\"}]}");
        bad(()->BehaviorProgram.parse(too),"33 steps");
        ok(BehaviorProgram.parse("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"DELAY\",\"delayMs\":30000},{\"op\":\"DELAY\",\"delayMs\":30000}]}").steps.size()==2,"60s delay");
        bad(()->BehaviorProgram.parse("{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"DELAY\",\"delayMs\":30000},{\"op\":\"DELAY\",\"delayMs\":30000},{\"op\":\"DELAY\",\"delayMs\":1}]}"),"60001 delay");
    }

    private static void contextStress() throws Exception {
        byte[] exact=new byte[(int)ContextDocumentContract.MAX_BYTES];
        ok(((Number)ContextDocumentContract.metadata("x","application/octet-stream","content://x",exact,1).get("bytes")).longValue()==ContextDocumentContract.MAX_BYTES,"context exact");
        bad(()->ContextDocumentContract.metadata("x","application/octet-stream","content://x",new byte[(int)ContextDocumentContract.MAX_BYTES+1],1),"context over");
    }

    private static KeyPair key(String curve)throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec(curve));return g.generateKeyPair();}
    private static void cryptoStress() throws Exception {
        long now=1700000000000L;KeyPair p256=key("secp256r1");Map<String,byte[]>trusted=new HashMap<String,byte[]>();trusted.put("k",p256.getPublic().getEncoded());
        for(int i=0;i<250;i++){
            String payload="{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"EMIT\",\"key\":\"k"+i+"\"}]}";
            String b=SignedBehaviorBundle.createForTestOrController("b"+i,now-1,now+60000,"k",payload,p256.getPrivate());
            SignedBehaviorBundle.verify(b,now,trusted);
            try{SignedBehaviorBundle.verify(b.replace("k"+i,"TAMPER"+i),now,trusted);throw new AssertionError("behavior tamper");}catch(IllegalArgumentException expected){}
            byte[]dex=new byte[64];R.nextBytes(dex);
            String m=SignedModuleManifest.createForTestOrController("m"+i,1,"science.transductive.nudge.modules.M"+i,dex,"k",now-1,now+60000,p256.getPrivate());
            SignedModuleManifest.verify(m,now,trusted,dex);dex[0]^=1;
            try{SignedModuleManifest.verify(m,now,trusted,dex);throw new AssertionError("dex tamper");}catch(IllegalArgumentException expected){}
        }
        ok(true,"crypto 250");
        for(String curve:new String[]{"secp384r1","secp521r1"}){
            KeyPair other=key(curve);Map<String,byte[]>tk=new HashMap<String,byte[]>();tk.put("k",other.getPublic().getEncoded());
            String payload="{\"version\":\"BEHAVIOR_PROGRAM/1\",\"steps\":[{\"op\":\"EMIT\",\"key\":\"x\"}]}";
            String b=SignedBehaviorBundle.createForTestOrController("curve",now-1,now+60000,"k",payload,other.getPrivate());
            bad(()->SignedBehaviorBundle.verify(b,now,tk),"behavior "+curve);
            byte[]dex=new byte[]{1,2,3};
            String m=SignedModuleManifest.createForTestOrController("curve",1,"science.transductive.nudge.modules.Curve",dex,"k",now-1,now+60000,other.getPrivate());
            bad(()->SignedModuleManifest.verify(m,now,tk,dex),"module "+curve);
        }
    }

    private static void affectStress() throws Exception {
        Map<String,Double>f=new HashMap<String,Double>(),h=new HashMap<String,Double>();f.put("raw",42.0);h.put("cue",0.5);
        ok(new AffectObservation("s","m","p",1,1.0,f,h).json().contains("UNCERTAIN_OBSERVATION_NOT_GROUND_TRUTH"),"affect authority");
        h.put("cue",1.01);bad(()->new AffectObservation("s","m","p",1,1.0,f,h),"affect bounds");
    }

    public static void main(String[]args)throws Exception{
        jsonStress();protocolStress();replayStress();automationStress();behaviorStress();contextStress();cryptoStress();affectStress();
        System.out.println("VM_STRESS_TESTS_PASS="+pass);
    }
}
