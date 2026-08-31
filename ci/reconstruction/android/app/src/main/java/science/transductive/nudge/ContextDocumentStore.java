package science.transductive.nudge;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import science.transductive.nudge.core.MiniJson;

public final class ContextDocumentStore {
    public static final long MAX_BYTES=2L*1024L*1024L;
    private ContextDocumentStore(){}
    public static final class Imported {public final String sha256,name,mimeType;public final long bytes;Imported(String h,String n,String m,long b){sha256=h;name=n;mimeType=m;bytes=b;}}

    public static Imported importUri(Context c,Uri uri){
        if(uri==null)throw new IllegalArgumentException("missing uri");
        String name=displayName(c,uri),mime=c.getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";
        byte[] data=readBounded(c,uri);String hash=sha(data);
        try{
            File dir=new File(c.getFilesDir(),"context-documents");dir.mkdirs();
            File bin=new File(dir,hash+".bin");if(!bin.isFile())atomic(bin,data);
            Map<String,Object>meta=new LinkedHashMap<String,Object>();meta.put("schema","CONSENTED_CONTEXT_DOCUMENT/1");meta.put("sha256",hash);meta.put("name",name);meta.put("mimeType",mime);meta.put("bytes",data.length);meta.put("sourceUri",uri.toString());meta.put("importedAtMs",System.currentTimeMillis());
            atomic(new File(dir,hash+".json"),(MiniJson.stringify(meta)+"\n").getBytes(StandardCharsets.UTF_8));
            return new Imported(hash,name,mime,data.length);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private static byte[] readBounded(Context c,Uri uri){
        try(InputStream in=c.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("open failed");byte[]buf=new byte[16384];long total=0;for(int n;(n=in.read(buf))!=-1;){total+=n;if(total>MAX_BYTES)throw new IOException("context document exceeds 2 MiB");out.write(buf,0,n);}return out.toByteArray();
        }catch(IOException e){throw new IllegalArgumentException(e.getMessage(),e);}
    }
    private static String displayName(Context c,Uri u){try(Cursor q=c.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(q!=null&&q.moveToFirst())return q.getString(0);}catch(Exception ignored){}return "document";}
    private static String sha(byte[]data){try{byte[]d=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static void atomic(File f,byte[]data)throws IOException{File t=new File(f.getParentFile(),f.getName()+".tmp");try(FileOutputStream o=new FileOutputStream(t)){o.write(data);o.getFD().sync();}if(!t.renameTo(f))throw new IOException("rename failed");}
}
