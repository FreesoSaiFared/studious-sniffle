package science.transductive.nudge;
import android.content.*;
import java.util.*;
public final class InteractionJournal {
    private static final String PREF="interaction_journal", KEY="seen", SEP="\n"; private static final int MAX=256;
    private InteractionJournal(){}
    public static synchronized Set<String> seen(Context c){String raw=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"");if(raw.trim().isEmpty())return Collections.<String>emptySet();return new LinkedHashSet<>(Arrays.asList(raw.split(SEP)));}
    public static synchronized boolean accept(Context c,String id){LinkedHashSet<String>s=new LinkedHashSet<>(seen(c));if(s.contains(id))return false;s.add(id);while(s.size()>MAX){Iterator<String>i=s.iterator();i.next();i.remove();}c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,join(s)).apply();return true;}
    private static String join(Collection<String> xs){StringBuilder b=new StringBuilder();for(String x:xs){if(b.length()>0)b.append(SEP);b.append(x);}return b.toString();}
}
