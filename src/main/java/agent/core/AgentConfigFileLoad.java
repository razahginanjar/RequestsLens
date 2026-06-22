package agent.core;

record AgentConfigFileLoad(boolean loaded, String path, boolean autoDiscovered) {
    static AgentConfigFileLoad none() {
        return new AgentConfigFileLoad(false, "", false);
    }
}
