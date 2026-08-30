package science.transductive.nudge.core;
import java.util.*;
public final class ReplayGuard {private final int max;private final LinkedHashSet<String>seen=new LinkedHashSet<String>();public ReplayGuard(int max){if(max<1)throw new IllegalArgumentException();this.max=max;}public synchronized boolean accept(String id){if(seen.contains(id))return false;seen.add(id);while(seen.size()>max){Iterator<String>it=seen.iterator();it.next();it.remove();}return true;}public synchronized Set<String> snapshot(){return Collections.unmodifiableSet(new HashSet<String>(seen));}}
