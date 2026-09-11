package io.guidein.audit.api;

import java.util.UUID;

public interface AuditLedger {
    AuditEvent append(AuditCommand command);
    IntegrityResult verify(UUID tenantId);

    record IntegrityResult(boolean valid, long verifiedEvents, Long firstInvalidSequence) {}
}

