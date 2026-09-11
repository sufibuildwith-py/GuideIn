package io.guidein.identity.application;

import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.identity.api.IdentityResolver;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcIdentityResolver implements IdentityResolver {
    private final JdbcClient jdbc;

    JdbcIdentityResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AuthenticatedSubject resolve(String issuer, String subject, String email, String displayName) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("OIDC issuer and subject are required");
        }
        UUID id = jdbc.sql("""
                INSERT INTO users(id, auth_issuer, external_subject, email_normalized, display_name, status)
                VALUES (:id, :issuer, :subject, :email, :name, 'ACTIVE')
                ON CONFLICT (auth_issuer, external_subject) DO UPDATE
                   SET email_normalized = EXCLUDED.email_normalized,
                       display_name = EXCLUDED.display_name
                 WHERE users.status = 'ACTIVE'
                RETURNING id
                """)
                .param("id", UUID.randomUUID())
                .param("issuer", issuer)
                .param("subject", subject)
                .param("email", normalizeEmail(email))
                .param("name", displayName)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Authenticated identity is disabled"));
        return new AuthenticatedSubject(id, issuer, subject);
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

