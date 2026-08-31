package science.transductive.nudge;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class AutomationJournal {
    private AutomationJournal(){}
    public static synchronized File persist(Context c,String jobId,String json){
        try{
            File d=new File(c.getFilesDir(),"automation-journal");d.mkdirs();
            String safe=jobId.replaceAll("[^A-Za-z0-9._-]","_");
            File target=new File(d,safe+".json"),tmp=new File(d,safe+".tmp");
            try(FileOutputStream o=new FileOutputStream(tmp)){o.write(json.getBytes(StandardCharsets.UTF_8));o.getFD().sync();}
            if(!tmp.renameTo(target))throw new IOException("automation journal rename failed");
            File[] fs=d.listFiles((dir,name)->name.endsWith(".json"));
            if(fs!=null&&fs.length>64){Arrays.sort(fs,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(int i=0;i<fs.length-64;i++)fs[i].delete();}
            return target;
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
