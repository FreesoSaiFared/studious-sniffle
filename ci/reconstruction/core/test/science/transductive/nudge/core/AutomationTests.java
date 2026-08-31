package science.transductive.nudge.core;
import java.util.*;

public final class AutomationTests {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static AutomationPlanner.Node n(int i,String t,String d,String cls,String id,int l,int top,int r,int b,boolean e,boolean c,boolean en,boolean pw,boolean f){
        return new AutomationPlanner.Node(i,t,d,cls,id,l,top,r,b,e,c,en,pw,f);
    }
    private static AutomationPlanner.Snapshot s(String pkg,AutomationPlanner.Node...n){
        return new AutomationPlanner.Snapshot(pkg,1,1080,2400,Arrays.asList(n));
    }
    public static void main(String[]args){
        AutomationPlanner.Snapshot x=s("com.openai.chatgpt",
            n(1,"","", "android.widget.EditText","composer",20,1800,900,1940,true,true,true,false,true),
            n(2,"","Send","android.widget.Button","send",920,1800,1060,1940,false,true,true,false,false));
        ok(AutomationPlanner.findComposer(x)==1,"focused composer");
        ok(AutomationPlanner.findSend(x,x.byIndex(1))==2,"semantic send");
        AutomationPlanner.Snapshot geo=s("com.openai.chatgpt",
            n(4,"","", "android.widget.EditText","",20,1700,850,1850,true,true,true,false,false),
            n(5,"","", "android.widget.ImageButton","",870,1710,1030,1850,false,true,true,false,false));
        ok(AutomationPlanner.findSend(geo,geo.byIndex(4))==5,"geometric send fallback");
        ok(AutomationPlanner.packageMatches(x,"com.openai.chatgpt"),"package fence");
        AutomationPlanner.Snapshot before=s("com.openai.chatgpt",
            n(1,"hello model","","android.widget.EditText","composer",0,0,1,1,true,true,true,false,true),
            n(2,"New chat","","android.widget.TextView","",0,0,1,1,false,false,true,false,false));
        AutomationPlanner.Snapshot after=s("com.openai.chatgpt",
            n(3,"hello model","","android.widget.TextView","",0,10,1,11,false,false,true,false,false),
            n(4,"answer line one","","android.widget.TextView","",0,20,1,21,false,false,true,false,false),
            n(5,"answer line two","","android.widget.TextView","",0,30,1,31,false,false,true,false,false),
            n(6,"Copy","","android.widget.Button","",0,40,1,41,false,true,true,false,false));
        ok(AutomationPlanner.projectResponse(before,after,"hello model").equals("answer line one\nanswer line two"),"response projection");
        ok(AutomationPlanner.stable(Arrays.asList("a","answer","answer","answer")),"stable response");
        ok(!AutomationPlanner.stable(Arrays.asList("answer","answer","changed")),"unstable response");
        ok(AutomationPlanner.fingerprint("x").length()==64,"sha256 fingerprint");
        System.out.println("AUTOMATION_TESTS_PASS="+pass);
    }
}
