package io.guidein.github.infrastructure.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Bounded cache; generation is part of authority. Provider refresh never persists a credential. */
public final class CachedInstallationTokens implements GitHubInstallationTokenProvider {
    private record Cached(long generation, InstallationToken token) { }
    private final Map<Long,Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final Clock clock;
    private final BiFunction<Long,Long,InstallationToken> mint;
    public CachedInstallationTokens(Clock clock, BiFunction<Long,Long,InstallationToken> mint) { this.clock=clock; this.mint=mint; }
    @Override public synchronized InstallationToken tokenFor(long installationId, long generation) {
        var existing = cache.get(installationId);
        Instant threshold = clock.instant().plusSeconds(300);
        if (existing != null && existing.generation()==generation && existing.token().expiresAt().isAfter(threshold)) return existing.token();
        InstallationToken token = mint.apply(installationId,generation);
        if (token.installationId()!=installationId || !token.expiresAt().isAfter(clock.instant()))
            throw new IllegalStateException("Invalid installation credential response");
        if (cache.size() >= 1000) cache.remove(cache.keySet().iterator().next());
        cache.put(installationId,new Cached(generation,token));
        return token;
    }
    @Override public synchronized void evict(long installationId) { cache.remove(installationId); }
}
