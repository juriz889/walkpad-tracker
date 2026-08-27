package com.walkingpad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Location of the Python/bleak bridge process, configurable via application.properties. */
@ConfigurationProperties(prefix = "walkingpad")
public class WalkingPadProperties {

    private String pythonExecutable = "bridge/.venv/bin/python";
    private String bridgeScript = "bridge/walkingpad_bridge.py";

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    public String getBridgeScript() {
        return bridgeScript;
    }

    public void setBridgeScript(String bridgeScript) {
        this.bridgeScript = bridgeScript;
    }
}
