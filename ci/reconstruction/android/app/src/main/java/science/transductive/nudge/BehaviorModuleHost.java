package science.transductive.nudge;

public interface BehaviorModuleHost {
    String promptChatGpt(String targetPackage,String prompt,long timeoutMs);
    void launchNudge(String prompt);
    void emit(String key,String value);
}
