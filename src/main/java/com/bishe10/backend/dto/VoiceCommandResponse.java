package com.bishe10.backend.dto;

import java.util.Map;

public class VoiceCommandResponse {
    private String intent;
    private Map<String, Object> params;
    private String reply;

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
