package science.transductive.nudge.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ContextDocumentTests {
    private static int pass=0;
    private static void ok(boolean v,String n){if(!v)throw new AssertionError(n);pass++;}
    private static void bad(Runnable r,String n){try{r.run();throw new AssertionError(n);}catch(IllegalArgumentException expected){pass++;}}

    public static void main(String[]args){
        byte[] data="hello context".getBytes(StandardCharsets.UTF_8);
        Map<String,Object> m=ContextDocumentContract.metadata(
            "diary.txt","text/plain","content://fixture/diary",data,123456789L);
        ok(((String)m.get("sha256")).length()==64,"sha256 present");
        ok("content://fixture/diary".equals(m.get("sourceUri")),"source provenance");
        ok(((Number)m.get("bytes")).longValue()==data.length&&"diary.txt".equals(m.get("name"))&&"text/plain".equals(m.get("mimeType")),"metadata identity");
        ok(((Number)m.get("importedAtMs")).longValue()==123456789L&&"USER_SELECTED_SOURCE".equals(m.get("authority")),"consent authority");
        bad(()->ContextDocumentContract.metadata("x","text/plain","",data,1L),"blank source rejected");
        System.out.println("CONTEXT_DOCUMENT_TESTS_PASS="+pass);
    }
}
