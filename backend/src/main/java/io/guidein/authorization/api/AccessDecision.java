package io.guidein.authorization.api;

public record AccessDecision(boolean allowed, String reasonCode) {
    public static AccessDecision allow() {
        return new AccessDecision(true, "AUTHORIZED");
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, reason);
    }
}

