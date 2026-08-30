package science.transductive.nudge;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import science.transductive.nudge.core.*;

public final class Outbox {
    private Outbox(){}
    private static File dir(Context c){File d=new File(c.getFilesDir(),"outbox");d.mkdirs();return d;}
    private static void atomic(File target,String body)throws IOException{File tmp=new File(target.getParentFile(),target.getName()+".tmp");try(FileOutputStream o=new FileOutputStream(tmp)){o.write(body.getBytes(StandardCharsets.UTF_8));o.getFD().sync();}if(!tmp.renameTo(target))throw new IOException("outbox rename failed");}
    public static synchronized void enqueueEvent(Context c,String eventId,String json){try{atomic(new File(dir(c),eventId+".evt"),eventId+"\n"+json);}catch(IOException e){throw new IllegalStateException(e);}}
    public static synchronized void enqueueAudio(Context c,String eventId,String interactionId,File audio){try{atomic(new File(dir(c),eventId+".aud"),eventId+"\n"+interactionId+"\n"+audio.getAbsolutePath());}catch(IOException e){throw new IllegalStateException(e);}}
    public static synchronized int pending(Context c){File[] f=dir(c).listFiles();return f==null?0:f.length;}
    public static synchronized int flush(Context c,WorkerClient w){int sent=0;File[] fs=dir(c).listFiles();if(fs==null)return 0;Arrays.sort(fs,Comparator.comparing(File::getName));for(File f:fs){try{List<String> lines=java.nio.file.Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);if(f.getName().endsWith(".evt")){String id=lines.get(0);String json=joinLines(lines,1);w.postEvent(json,id);if(f.delete())sent++;}else if(f.getName().endsWith(".aud")){String id=lines.get(0),interaction=lines.get(1),path=lines.get(2);File a=new File(path);if(!a.isFile())throw new IOException("audio missing");String ref=w.uploadAudio(a,interaction,id);Map<String,Object> m=new LinkedHashMap<>();m.put("protocol",Protocol.VERSION);m.put("eventId",id);m.put("interactionId",interaction);m.put("atMs",System.currentTimeMillis());m.put("audioRef",ref);w.postEvent(MiniJson.stringify(m),id+"-meta");if(f.delete()){a.delete();sent++;}}}catch(Exception ignored){/* idempotency keys make a later retry safe */}}return sent;}
    private static String joinLines(List<String> lines,int start){StringBuilder b=new StringBuilder();for(int i=start;i<lines.size();i++){if(b.length()>0)b.append("\n");b.append(lines.get(i));}return b.toString();}
}
