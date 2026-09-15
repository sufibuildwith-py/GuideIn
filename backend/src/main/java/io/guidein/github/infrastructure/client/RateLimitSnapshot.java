package io.guidein.github.infrastructure.client;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public record RateLimitSnapshot(Long limit, Long remaining, Long used, Instant reset,
                                String resource, Instant retryAfter, String requestId) {
    public static RateLimitSnapshot from(HttpHeaders headers, Instant now) {
        Long reset = number(headers, "x-ratelimit-reset");
        Instant retry = null;
        String value = headers.firstValue("retry-after").orElse("");
        try { retry = now.plusSeconds(Math.max(0, Long.parseLong(value))); }
        catch (RuntimeException ignored) {
            try { retry = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (RuntimeException alsoIgnored) { /* Absent/malformed headers use conservative fallback. */ }
        }
        Instant resetTime = null;
        try { if (reset != null) resetTime = Instant.ofEpochSecond(reset); } catch (RuntimeException ignored) { }
        return new RateLimitSnapshot(number(headers, "x-ratelimit-limit"), number(headers, "x-ratelimit-remaining"),
                number(headers, "x-ratelimit-used"), resetTime, safe(headers, "x-ratelimit-resource"), retry,
                safe(headers, "x-github-request-id"));
    }
    public Instant notBefore(Instant now, int attempt) {
        Instant result = retryAfter != null ? retryAfter : Long.valueOf(0).equals(remaining) && reset != null ? reset
                : now.plusSeconds(Math.min(3600, 60L << Math.min(6, Math.max(0, attempt - 1))));
        return result.isAfter(now) ? result : now.plusSeconds(1);
    }
    private static Long number(HttpHeaders headers, String name) {
        try { long value = Long.parseLong(headers.firstValue(name).orElse("")); return value >= 0 ? value : null; }
        catch (RuntimeException ignored) { return null; }
    }
    private static String safe(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse("");
        return value.matches("[A-Za-z0-9:_-]{0,100}") ? value : "";
    }
}
