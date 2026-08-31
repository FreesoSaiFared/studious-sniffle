package science.transductive.nudge;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import science.transductive.nudge.core.MiniJson;
import science.transductive.nudge.core.SignedModuleManifest;

public final class SignedModuleInstaller {
    public static final class Activation {
        public final String moduleId,dexSha256,entryClass,previousModuleId;
        public final long version;
        Activation(String id,long v,String hash,String cls,String prev){moduleId=id;version=v;dexSha256=hash;entryClass=cls;previousModuleId=prev;}
    }
    public static final class Loaded {
        public final SignedModuleManifest.Verified manifest;
        public final BehaviorModule module;
        Loaded(SignedModuleManifest.Verified m,BehaviorModule b){manifest=m;module=b;}
    }

    private SignedModuleInstaller(){}

    public static synchronized Activation installAndActivate(Context c,String manifestJson,byte[]dex){
        SignedModuleManifest.Verified v=SignedModuleManifest.verify(
            manifestJson,System.currentTimeMillis(),TrustedBehaviorKeys.all(c),dex);
        File root=root(c),dir=new File(root,v.moduleId+"/"+v.version+"-"+v.dexSha256.substring(0,16));
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("module directory");
        File dexFile=new File(dir,"module.dex");
        if(!dexFile.isFile())writeReadOnly(dexFile,dex);
        byte[]onDisk=read(dexFile);
        if(!SignedModuleManifest.sha256(onDisk).equals(v.dexSha256))throw new IllegalStateException("stored dex hash mismatch");
        if(dexFile.canWrite()&&!dexFile.setReadOnly())throw new IllegalStateException("dex must be read-only before load");

        Loaded loaded=loadVerified(c,manifestJson,dexFile);
        if(!BehaviorModule.API_VERSION.equals(loaded.module.apiVersion()))throw new IllegalArgumentException("module API mismatch");
        if(!loaded.module.selfTest())throw new IllegalArgumentException("module selfTest failed");

        Map<String,Object> old=readState(c);
        String previous="";
        Object current=old.get("current");
        if(current instanceof Map){
            Object id=((Map<?,?>)current).get("moduleId");if(id!=null)previous=String.valueOf(id);
        }
        LinkedHashMap<String,Object> next=new LinkedHashMap<String,Object>();
        next.put("schema","SIGNED_MODULE_ACTIVE_STATE/1");
        next.put("current",entry(v,manifestJson,dexFile));
        if(current instanceof Map)next.put("previous",current);
        else if(old.get("previous") instanceof Map)next.put("previous",old.get("previous"));
        next.put("activatedAtMs",System.currentTimeMillis());
        writeAtomic(stateFile(c),(MiniJson.stringify(next)+"\n").getBytes(StandardCharsets.UTF_8));
        return new Activation(v.moduleId,v.version,v.dexSha256,v.entryClass,previous);
    }

    public static synchronized Loaded loadActive(Context c){
        Map<String,Object> state=readState(c);Object current=state.get("current");
        if(!(current instanceof Map))return null;
        return loadEntry(c,(Map<?,?>)current);
    }

    public static synchronized Activation rollback(Context c){
        Map<String,Object> state=readState(c);
        Object current=state.get("current"),previous=state.get("previous");
        if(!(previous instanceof Map))throw new IllegalStateException("no previous module");
        Loaded verifiedPrevious=loadEntry(c,(Map<?,?>)previous);
        Loaded verifiedCurrent=current instanceof Map?loadEntry(c,(Map<?,?>)current):null;

        LinkedHashMap<String,Object> next=new LinkedHashMap<String,Object>();
        next.put("schema","SIGNED_MODULE_ACTIVE_STATE/1");
        next.put("current",previous);
        if(current instanceof Map)next.put("previous",current);
        next.put("activatedAtMs",System.currentTimeMillis());
        writeAtomic(stateFile(c),(MiniJson.stringify(next)+"\n").getBytes(StandardCharsets.UTF_8));

        return new Activation(
            verifiedPrevious.manifest.moduleId,verifiedPrevious.manifest.version,
            verifiedPrevious.manifest.dexSha256,verifiedPrevious.manifest.entryClass,
            verifiedCurrent==null?"":verifiedCurrent.manifest.moduleId);
    }

    private static Loaded loadEntry(Context c,Map<?,?>entry){
        String manifestJson=String.valueOf(entry.get("manifestJson"));
        File dexFile=checkedModuleFile(c,String.valueOf(entry.get("dexPath")));
        if(!dexFile.isFile()||dexFile.canWrite())throw new IllegalStateException("active dex missing or writable");
        return loadVerified(c,manifestJson,dexFile);
    }

    private static Loaded loadVerified(Context c,String manifestJson,File dexFile){
        byte[]dex=read(dexFile);
        SignedModuleManifest.Verified v=SignedModuleManifest.verify(
            manifestJson,System.currentTimeMillis(),TrustedBehaviorKeys.all(c),dex);
        try{
            File opt=new File(c.getCodeCacheDir(),"signed-module-opt/"+v.moduleId);
            opt.mkdirs();
            DexClassLoader loader=new DexClassLoader(dexFile.getAbsolutePath(),opt.getAbsolutePath(),null,c.getClassLoader());
            Class<?> cls=loader.loadClass(v.entryClass);
            Object instance=cls.getDeclaredConstructor().newInstance();
            if(!(instance instanceof BehaviorModule))throw new IllegalArgumentException("entry does not implement BehaviorModule");
            BehaviorModule module=(BehaviorModule)instance;
            if(!BehaviorModule.API_VERSION.equals(module.apiVersion()))throw new IllegalArgumentException("module API mismatch");
            if(!module.selfTest())throw new IllegalArgumentException("module selfTest failed");
            return new Loaded(v,module);
        }catch(ReflectiveOperationException e){throw new IllegalArgumentException("module load failed",e);}
    }

    private static Map<String,Object> entry(SignedModuleManifest.Verified v,String manifestJson,File dexFile){
        LinkedHashMap<String,Object>m=new LinkedHashMap<String,Object>();
        m.put("moduleId",v.moduleId);m.put("version",v.version);m.put("dexSha256",v.dexSha256);m.put("entryClass",v.entryClass);
        m.put("dexPath",dexFile.getAbsolutePath());m.put("manifestJson",manifestJson);return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> readState(Context c){
        File f=stateFile(c);if(!f.isFile())return new LinkedHashMap<String,Object>();
        try{
            Object x=MiniJson.parse(new String(read(f),StandardCharsets.UTF_8));
            return x instanceof Map?(Map<String,Object>)x:new LinkedHashMap<String,Object>();
        }catch(Exception e){throw new IllegalStateException("module state invalid",e);}
    }

    private static File root(Context c){File r=new File(c.getFilesDir(),"signed-modules");if(!r.exists())r.mkdirs();return r;}
    private static File stateFile(Context c){File d=new File(c.getFilesDir(),"module-state");if(!d.exists())d.mkdirs();return new File(d,"active.json");}

    private static File checkedModuleFile(Context c,String path){
        try{
            File root=root(c).getCanonicalFile(),f=new File(path).getCanonicalFile();
            if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IllegalStateException("module path escaped private root");
            return f;
        }catch(IOException e){throw new IllegalStateException(e);}
    }

    private static byte[] read(File f){
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[]b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }

    private static void writeReadOnly(File target,byte[]data){
        writeAtomic(target,data);
        if(!target.setReadOnly()||target.canWrite())throw new IllegalStateException("failed to make dex read-only");
    }

    private static void writeAtomic(File target,byte[]data){
        try{
            File tmp=new File(target.getParentFile(),target.getName()+".tmp");
            if(tmp.exists())tmp.delete();
            try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);out.getFD().sync();}
            if(target.exists()&&!target.delete())throw new IOException("replace target");
            if(!tmp.renameTo(target))throw new IOException("atomic rename");
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
