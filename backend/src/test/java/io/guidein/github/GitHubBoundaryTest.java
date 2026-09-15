package io.guidein.github;

import static org.assertj.core.api.Assertions.*;
import io.guidein.github.infrastructure.auth.*;
import io.guidein.github.infrastructure.webhook.WebhookAuthentication;
import io.guidein.github.infrastructure.client.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class GitHubBoundaryTest {
    private static final byte[] SECRET = "phase2-synthetic-webhook-secret".getBytes(StandardCharsets.UTF_8);
    private final WebhookAuthentication authentication = new WebhookAuthentication(() -> List.of(
            new GitHubWebhookSecretProvider.VersionedSecret("current", SECRET)));

    private static String sign(byte[] raw, byte[] key) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key,"HmacSHA256"));
        return "sha256="+HexFormat.of().formatHex(mac.doFinal(raw));
    }

    @Test void rawUnicodeAndWhitespaceAreCryptographicallyBound() throws Exception {
        byte[] raw = "{\"title\":\"नमस्ते 🌏\"}".getBytes(StandardCharsets.UTF_8);
        String signature=sign(raw,SECRET);
        assertThat(authentication.verify(raw,signature)).isEqualTo("current");
        assertThat(authentication.verify(raw,"sha256="+signature.substring(7).toUpperCase(Locale.ROOT))).isEqualTo("current");
        assertThatThrownBy(() -> authentication.verify((new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),signature))
                .isInstanceOf(WebhookAuthentication.InvalidSignature.class);
        assertThatThrownBy(() -> authentication.verify("{}".getBytes(StandardCharsets.UTF_8),signature))
                .isInstanceOf(WebhookAuthentication.InvalidSignature.class);
    }

    @ParameterizedTest @ValueSource(strings={"", "sha1=abc", "sha256=xyz", "sha256=00", "SHA256=00", "sha256=0000000000000000000000000000000000000000000000000000000000000000"})
    void malformedAndForgedSignaturesFail(String value) {
        assertThatThrownBy(() -> authentication.verify(new byte[]{1},value)).isInstanceOf(WebhookAuthentication.InvalidSignature.class);
    }
    @Test void missingSignatureFails() {
        assertThatThrownBy(() -> authentication.verify(new byte[0],null)).isInstanceOf(WebhookAuthentication.InvalidSignature.class);
    }
    @Test void boundedRotationAcceptsPreviousWithoutExposingIt() throws Exception {
        byte[] previous="phase2-previous-webhook-secret".getBytes(StandardCharsets.UTF_8);
        var verifier=new WebhookAuthentication(() -> List.of(new GitHubWebhookSecretProvider.VersionedSecret("current",SECRET),
                new GitHubWebhookSecretProvider.VersionedSecret("previous",previous)));
        assertThat(verifier.verify(new byte[]{2},sign(new byte[]{2},previous))).isEqualTo("previous");
        assertThat(new GitHubWebhookSecretProvider.VersionedSecret("current",SECRET).toString()).doesNotContain(new String(SECRET,StandardCharsets.UTF_8));
    }
    @Test void actualStreamSizeNotDeclaredLengthControlsAcceptance() throws Exception {
        for (int length : new int[]{7,8}) assertThat(WebhookAuthentication.readBounded(new ByteArrayInputStream(new byte[length]),8)).hasSize(length);
        assertThatThrownBy(() -> WebhookAuthentication.readBounded(new ByteArrayInputStream(new byte[9]),8))
                .isInstanceOf(WebhookAuthentication.PayloadTooLarge.class);
    }
    @Test void tokensRemainOpaqueAndCacheUsesExpiryAndGeneration() {
        Instant now=Instant.parse("2026-09-15T00:00:00Z");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        List<String> tokens=List.of("ghs_old_fixture","opaque."+"x".repeat(3000),"!unexpected-form!");
        var cache=new CachedInstallationTokens(Clock.fixed(now,ZoneOffset.UTC),(installation,generation) ->
                new GitHubInstallationTokenProvider.InstallationToken(tokens.get(calls.getAndIncrement()),now.plusSeconds(3600),installation,Map.of("contents","read")));
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo(tokens.get(0));
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo(tokens.get(0));
        cache.evict(1);
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo(tokens.get(1));
        assertThat(cache.tokenFor(1,2).secret()).isEqualTo(tokens.get(2));
        assertThat(cache.tokenFor(1,2).toString()).doesNotContain(tokens.get(2));
        assertThat(calls).hasValue(3);
    }
    @Test void rateRetryPrecedenceAndSecondaryFloorAreExplicit() {
        Instant now=Instant.parse("2026-09-15T00:00:00Z");
        var headers=java.net.http.HttpHeaders.of(Map.of("retry-after",List.of("120"),"x-ratelimit-remaining",List.of("0"),
                "x-ratelimit-reset",List.of(""+now.plusSeconds(300).getEpochSecond())),(a,b)->true);
        assertThat(RateLimitSnapshot.from(headers,now).notBefore(now,1)).isEqualTo(now.plusSeconds(120));
        var reset=java.net.http.HttpHeaders.of(Map.of("x-ratelimit-remaining",List.of("0"),"x-ratelimit-reset",List.of(""+now.plusSeconds(300).getEpochSecond())),(a,b)->true);
        assertThat(RateLimitSnapshot.from(reset,now).notBefore(now,1)).isEqualTo(now.plusSeconds(300));
        assertThat(RateLimitSnapshot.from(java.net.http.HttpHeaders.of(Map.of(),(a,b)->true),now).notBefore(now,1)).isEqualTo(now.plusSeconds(60));
    }
    @Test void nearExpiryRefreshesAndExpiredCredentialsAreNeverReused() {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-09-15T00:00:00Z"));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var cache=new CachedInstallationTokens(clock,(installation,generation)->new GitHubInstallationTokenProvider.InstallationToken(
                "opaque-"+calls.incrementAndGet(),now.get().plusSeconds(3600),installation,Map.of()));
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo("opaque-1");
        now.set(now.get().plusSeconds(3300));
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo("opaque-2");
        now.set(now.get().plusSeconds(3601));
        assertThat(cache.tokenFor(1,1).secret()).isEqualTo("opaque-3");
        var expired=new CachedInstallationTokens(clock,(installation,generation)->new GitHubInstallationTokenProvider.InstallationToken("expired",now.get().minusSeconds(1),installation,Map.of()));
        assertThatThrownBy(()->expired.tokenFor(1,1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void providerUrlsAndPaginationCannotEscapeOrigin() {
        try(var transport=new GitHubTransport(java.net.URI.create("https://api.github.com"),Duration.ofSeconds(1),Duration.ofSeconds(2),1024,false,Clock.systemUTC())) {
            for(String path:List.of("https://evil.test", "//evil.test/x", "/../secret", "/x#fragment"))
                assertThatThrownBy(() -> transport.resolve(path)).isInstanceOf(ProviderFailure.class);
            var headers=java.net.http.HttpHeaders.of(Map.of("Link",List.of("<https://evil.test/steal?page=2>; rel=\"next\"")),(a,b)->true);
            assertThatThrownBy(() -> transport.nextPage("/installation/repositories?per_page=100",headers)).isInstanceOf(ProviderFailure.class);
        }
    }
}
