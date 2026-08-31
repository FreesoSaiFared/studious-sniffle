package science.transductive.nudge.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class AutomationPlanner {
    private AutomationPlanner() {}

    public static final class Node {
        public final int index, left, top, right, bottom;
        public final String text, desc, className, viewId;
        public final boolean editable, clickable, enabled, password, focused;
        public Node(int index,String text,String desc,String className,String viewId,
                    int left,int top,int right,int bottom,
                    boolean editable,boolean clickable,boolean enabled,boolean password,boolean focused){
            this.index=index;this.text=n(text);this.desc=n(desc);this.className=n(className);this.viewId=n(viewId);
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
            this.editable=editable;this.clickable=clickable;this.enabled=enabled;this.password=password;this.focused=focused;
        }
        public int cx(){return left+(right-left)/2;}
        public int cy(){return top+(bottom-top)/2;}
        public int height(){return Math.max(1,bottom-top);}
        public String semantic(){return (text+" "+desc+" "+viewId+" "+className).toLowerCase(Locale.ROOT);}
    }

    public static final class Snapshot {
        public final String packageName;
        public final long generation;
        public final int screenWidth, screenHeight;
        public final List<Node> nodes;
        public Snapshot(String packageName,long generation,int screenWidth,int screenHeight,List<Node>nodes){
            this.packageName=n(packageName);this.generation=generation;this.screenWidth=screenWidth;this.screenHeight=screenHeight;
            this.nodes=Collections.unmodifiableList(new ArrayList<Node>(nodes));
        }
        public Node byIndex(int index){for(Node x:nodes)if(x.index==index)return x;return null;}
    }

    public static int findComposer(Snapshot s){
        int best=-1;long bestScore=Long.MIN_VALUE;
        for(Node x:s.nodes){
            if(!x.enabled||!x.editable||x.password)continue;
            long score=0;
            if(x.focused)score+=100000;
            String sem=x.semantic();
            if(sem.contains("edittext")||sem.contains("textfield"))score+=5000;
            if(sem.contains("prompt")||sem.contains("composer")||sem.contains("message"))score+=4000;
            score+=Math.max(0,x.top);
            score+=Math.max(0,x.right-x.left);
            if(score>bestScore){bestScore=score;best=x.index;}
        }
        return best;
    }

    public static int findSend(Snapshot s,Node composer){
        if(composer==null)return -1;
        int best=-1;long bestScore=Long.MIN_VALUE;
        for(Node x:s.nodes){
            if(!x.enabled||!x.clickable||x.password||x.index==composer.index)continue;
            String sem=x.semantic();
            if(containsAny(sem,"microphone"," mic ","voice","attach","camera","stop","cancel"))continue;
            long score=0;
            if(containsAny(sem,"send","submit","arrow_upward","ic_send","message_send"))score+=100000;
            int dx=x.cx()-composer.cx(),dy=Math.abs(x.cy()-composer.cy());
            boolean adjacent=x.left>=composer.left && dx>=0 && dy<=Math.max(composer.height()*2,96);
            if(adjacent)score+=10000-Math.min(9000,Math.abs(dx)+dy);
            if(x.top>=composer.top-composer.height()&&x.bottom<=composer.bottom+composer.height()*2)score+=1000;
            if(score>bestScore&&score>0){bestScore=score;best=x.index;}
        }
        return best;
    }

    public static boolean packageMatches(Snapshot s,String expectedPackage){
        return s!=null && expectedPackage!=null && expectedPackage.equals(s.packageName);
    }

    public static String projectResponse(Snapshot before,Snapshot after,String prompt){
        if(after==null)return "";
        Set<String> old=new HashSet<String>();
        if(before!=null)for(Node n:before.nodes)if(!n.editable)addText(old,n.text);
        final String p=n(prompt).trim();
        List<Node> ordered=new ArrayList<Node>(after.nodes);
        Collections.sort(ordered,new Comparator<Node>(){public int compare(Node a,Node b){
            int c=Integer.compare(a.top,b.top);if(c!=0)return c;c=Integer.compare(a.left,b.left);if(c!=0)return c;return Integer.compare(a.index,b.index);
        }});
        LinkedHashSet<String> out=new LinkedHashSet<String>();
        for(Node node:ordered){
            if(node.editable||!node.enabled)continue;
            String t=n(node.text).trim();
            if(t.isEmpty()||t.equals(p)||old.contains(t)||isChrome(t))continue;
            out.add(t);
        }
        StringBuilder b=new StringBuilder();
        for(String t:out){if(b.length()>0)b.append("\n");b.append(t);}
        return b.toString().trim();
    }

    public static boolean stable(List<String> samples){
        if(samples==null||samples.size()<3)return false;
        String a=n(samples.get(samples.size()-1)).trim();
        String b=n(samples.get(samples.size()-2)).trim();
        String c=n(samples.get(samples.size()-3)).trim();
        return !a.isEmpty()&&a.equals(b)&&a.equals(c);
    }

    public static String fingerprint(String text){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest(n(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x&255));return b.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }

    private static boolean isChrome(String text){
        String x=text.toLowerCase(Locale.ROOT).trim();
        return x.equals("send")||x.equals("submit")||x.equals("stop")||x.equals("stop generating")||
               x.equals("regenerate")||x.equals("copy")||x.equals("share")||x.equals("edit")||
               x.equals("read aloud")||x.equals("like")||x.equals("dislike");
    }
    private static void addText(Set<String>s,String v){String x=n(v).trim();if(!x.isEmpty())s.add(x);}
    private static boolean containsAny(String s,String...xs){for(String x:xs)if(s.contains(x))return true;return false;}
    private static String n(String s){return s==null?"":s;}
}
