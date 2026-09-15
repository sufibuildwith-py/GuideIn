package io.guidein.github.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.UUID;

public interface GitHubIntegration {
    Preparation prepare(AuthenticatedSubject subject,UUID tenantId);
    UUID bind(AuthenticatedSubject subject,UUID tenantId,String state,String verifier,String code,long installationId);
    Receipt accept(byte[] raw,String signature,String delivery,String hook,String event,UUID correlationId);
    boolean processNext(UUID tenantId);
    void redrive(AuthenticatedSubject subject,UUID tenantId,UUID deliveryId,UUID correlationId);
    record Preparation(String installationUrl,String authorizationUrl,String state,String verifier) {
        @Override public String toString() { return "Preparation[redacted]"; }
    }
    record Receipt(UUID id,String status,boolean duplicate) { }
}
