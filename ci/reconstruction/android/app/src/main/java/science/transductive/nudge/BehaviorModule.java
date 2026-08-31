package science.transductive.nudge;

public interface BehaviorModule {
    String API_VERSION="SELF_NUDGE_BEHAVIOR_MODULE/1";
    String apiVersion();
    boolean selfTest();
    String execute(BehaviorModuleHost host,String inputJson);
}
