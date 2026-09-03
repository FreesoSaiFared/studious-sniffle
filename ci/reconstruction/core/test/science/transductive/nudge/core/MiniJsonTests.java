package science.transductive.nudge.core;

import java.util.*;

public final class MiniJsonTests {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void bad(Runnable r,String n){try{r.run();throw new AssertionError(n);}catch(IllegalArgumentException expected){pass++;}}
    public static void main(String[]args){
        Object zero=MiniJson.parse("0");
        ok(zero instanceof Long && ((Long)zero).longValue()==0L && "0".equals(MiniJson.stringify(zero)),"integer remains canonical Long");
        Object neg=MiniJson.parse("-42");
        ok(neg instanceof Long && ((Long)neg).longValue()==-42L,"negative integer Long");
        Object fp=MiniJson.parse("1.25");
        ok(fp instanceof Double && ((Double)fp).doubleValue()==1.25,"fraction Double");
        bad(()->MiniJson.parse("{\"a\":1,\"a\":2}"),"duplicate key rejected");
        bad(()->MiniJson.parse("\"line\nraw\""),"raw control rejected");
        bad(()->MiniJson.parse("1."),"fraction requires digit");
        ok("line\nfeed".equals(MiniJson.parse("\"line\\nfeed\"")),"escaped control accepted");
        String nested="{\"a\":[1,true,null,{\"z\":-2}],\"b\":\"x\"}";
        ok(nested.equals(MiniJson.stringify(MiniJson.parse(nested))),"nested canonical roundtrip");
        System.out.println("MINI_JSON_TESTS_PASS="+pass);
    }
}
