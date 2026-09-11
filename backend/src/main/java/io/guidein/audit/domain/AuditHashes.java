package io.guidein.audit.domain;

import io.guidein.audit.api.AuditCommand;
import io.guidein.platform.api.Digests;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class AuditHashes {
    private AuditHashes() {}

    public static byte[] eventHash(AuditCommand command, long sequence, byte[] payloadHash, byte[] previousHash) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, command.tenantId().toString());
            out.writeLong(sequence);
            write(out, command.occurredAt().toString());
            write(out, command.actorType().name());
            write(out, command.actorId() == null ? "" : command.actorId().toString());
            write(out, command.action());
            write(out, command.resourceType());
            write(out, command.resourceId() == null ? "" : command.resourceId().toString());
            write(out, command.correlationId().toString());
            write(out, payloadHash);
            write(out, previousHash == null ? new byte[0] : previousHash);
            out.flush();
            return Digests.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        write(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }
}
