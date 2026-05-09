package com.agent.model;

import java.time.Instant;

public record AgentStep(
        String emoji,
        String message,
        Instant timestamp,
        String type,
        String emailContent,
        String generatedReply
) {

    public static AgentStep info(String emoji, String message) {
        return new AgentStep(emoji, message, Instant.now(), "info", null, null);
    }

    public static AgentStep success(String emoji, String message) {
        return new AgentStep(emoji, message, Instant.now(), "success", null, null);
    }

    public static AgentStep error(String message) {
        return new AgentStep("ERR", message, Instant.now(), "error", null, null);
    }

    public AgentStep withEmailContent(String content) {
        return new AgentStep(emoji, message, timestamp, type, content, generatedReply);
    }

    public AgentStep withGeneratedReply(String reply) {
        return new AgentStep(emoji, message, timestamp, type, emailContent, reply);
    }
}
