package io.guidein.github.infrastructure.client;

import io.guidein.jobs.api.FailureCategory;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** All destinations derive from administrator configuration, never payload URL fields. */
public final class GitHubTransport implements AutoCloseable {
    public static final String API_VERSION = "2026-03-10";
    private final HttpClient http;
    private final URI origin;
    private final Duration timeout;
    private final int maximumBytes;
    private final Clock clock;

    public GitHubTransport(URI origin, Duration connectTimeout, Duration timeout, int maximumBytes,
                           boolean allowLoopbackTest, Clock clock) {
        boolean github = "https".equals(origin.getScheme()) && List.of("api.github.com","github.com").contains(origin.getHost())
                && (origin.getPort() == -1 || origin.getPort() == 443);
        boolean local = allowLoopbackTest && "http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost());
        if ((!github && !local) || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")) || maximumBytes < 1
                || maximumBytes > 26_214_400 || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid GitHub transport configuration");
        this.origin = origin;
        this.timeout = timeout;
        this.maximumBytes = maximumBytes;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Response request(String method, String path, String authorization, byte[] body, Map<String,String> extra) {
        URI uri = resolve(path);
        if (!List.of("GET","POST","DELETE").contains(method)) throw new IllegalArgumentException("Unsupported provider method");
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", path.startsWith("/login/oauth/") ? "application/json" : "application/vnd.github+json").header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "GuideIn/2.0").header("Authorization", authorization)
                .header("Content-Type", "application/json");
        extra.forEach((key, value) -> {
            if (!key.equals("If-None-Match")) throw new IllegalArgumentException("Unsupported provider header");
            request.header(key, value);
        });
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        var future = http.sendAsync(request.build(), ignored -> new LimitedBody(maximumBytes));
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body(), response.headers(),
                    RateLimitSnapshot.from(response.headers(), clock.instant()));
        } catch (InterruptedException ignored) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new ProviderFailure(FailureCategory.TIMEOUT, null);
        } catch (java.util.concurrent.TimeoutException ignored) {
            future.cancel(true); throw new ProviderFailure(FailureCategory.TIMEOUT, null);
        } catch (java.util.concurrent.ExecutionException ignored) {
            future.cancel(true); throw new ProviderFailure(FailureCategory.TRANSIENT_PROVIDER, null);
        }
    }

    public URI resolve(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.contains("..") || path.contains("#") || path.length() > 4096)
            throw new ProviderFailure(FailureCategory.INVALID_INPUT, null);
        URI resolved;
        try { resolved = origin.resolve(path); } catch (RuntimeException ignored) { throw new ProviderFailure(FailureCategory.INVALID_INPUT, null); }
        if (!sameOrigin(resolved)) throw new ProviderFailure(FailureCategory.INVALID_INPUT, null);
        return resolved;
    }

    /** Follow Link next only on the same origin AND endpoint path, with bounded page parameters. */
    public String nextPage(String current, java.net.http.HttpHeaders headers) {
        String link = headers.firstValue("Link").orElse("");
        if (link.length() > 8192) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE, null);
        for (String part : link.split(",")) {
            if (!part.matches(".*;\\s*rel=\"next\".*")) continue;
            int left = part.indexOf('<'), right = part.indexOf('>');
            if (left < 0 || right <= left) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE, null);
            try {
                URI next = resolve(current).resolve(part.substring(left+1,right));
                if (!sameOrigin(next) || !next.getPath().equals(resolve(current).getPath())
                        || next.getFragment() != null || next.getRawQuery() == null
                        || !next.getRawQuery().matches("(?:page=[1-9][0-9]{0,5}|per_page=100)(?:&(?:page=[1-9][0-9]{0,5}|per_page=100))*"))
                    throw new IllegalArgumentException();
                return next.getRawPath()+"?"+next.getRawQuery();
            } catch (RuntimeException ignored) { throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE, null); }
        }
        return null;
    }

    private boolean sameOrigin(URI uri) {
        return origin.getScheme().equals(uri.getScheme()) && origin.getHost().equals(uri.getHost())
                && origin.getPort()==uri.getPort() && uri.getUserInfo()==null;
    }
    @Override public void close() { http.shutdownNow(); }

    public record Response(int status, byte[] body, java.net.http.HttpHeaders headers, RateLimitSnapshot rate) {
        @Override public String toString() { return "GitHubResponse[status="+status+",body=redacted]"; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer next : buffers) {
                if (next.remaining() > limit-buffer.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Provider response exceeds limit")); return;
                }
                byte[] bytes = new byte[next.remaining()]; next.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable ignored) { result.completeExceptionally(new IllegalStateException("Provider response failed")); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
