package com.ruoyi.union.adapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.utils.JsonUtil;
import com.ruoyi.union.dtk.api.utils.SignMd5Util;

/** Order-only DTK transport: bounded requests, per-instance credentials, no secret-bearing logs. */
public final class DtkOrderEvidenceClient {
    @FunctionalInterface public interface Exchange { String get(URI uri, Duration timeout) throws Exception; }
    @FunctionalInterface public interface EvidenceSink { void retain(String text, Instant fetchedAt); }
    public record Response<T>(T data, String rawJson, Instant fetchedAt) {
        @Override public String toString() { return "DtkOrderResponse[data=<redacted>, rawJson=<redacted>, fetchedAt=" + fetchedAt + "]"; }
    }
    private final String appKey;
    private final String appSecret;
    private final Duration timeout;
    private final Exchange exchange;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DtkOrderEvidenceClient(String appKey, String appSecret, Duration timeout) {
        this(appKey, appSecret, timeout, httpExchange(timeout), Clock.systemUTC());
    }

    public DtkOrderEvidenceClient(String appKey, String appSecret, Duration timeout, Exchange exchange, Clock clock) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()
            || timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("DTK order credentials and positive timeout required");
        this.appKey = appKey; this.appSecret = appSecret; this.timeout = timeout;
        this.exchange = Objects.requireNonNull(exchange); this.clock = Objects.requireNonNull(clock);
    }

    public <T> Response<T> execute(DtkApiRequest<T> request, EvidenceSink evidence) {
        Objects.requireNonNull(request); Objects.requireNonNull(evidence);
        String path = request.requestUrl();
        // Do not allow a request implementation to redirect signed credentials to another host.
        if (!java.util.Set.of("/tb-service/get-order-details", "/dels/jd/order/get-official-order-list",
            "/tb-service/get-refund-order-list", "/tb-service/get-foul-order-list",
            "/dels/pdd/order/incrementSearch").contains(path))
            throw new IllegalArgumentException("Unsupported DTK order endpoint");
        final String text;
        try {
            Map<String,String> params = new TreeMap<>(request.getTextParams());
            String timer = Long.toString(clock.millis());
            String nonce = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1000000));
            params.put("appKey", appKey); params.put("timer", timer); params.put("nonce", nonce);
            params.put("sign", SignMd5Util.sign("appKey=" + appKey + "&timer=" + timer + "&nonce=" + nonce, appSecret));
            String query = params.entrySet().stream().filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
            text = exchange.get(URI.create("https://openapi.dataoke.com/api" + path + "?" + query), timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DTK order request interrupted");
        } catch (Exception failure) {
            // HTTP exceptions can contain the full signed URI: do not propagate them or their messages.
            throw new IllegalStateException("DTK order request failed");
        }
        Instant fetchedAt = clock.instant();
        if (text == null) throw new IllegalStateException("DTK order response missing");
        evidence.retain(text, fetchedAt); // Persist even errors, malformed JSON and empty pages before decoding.
        try {
            return new Response<>(JsonUtil.jsonToPojoByTypeReference(text, request.responseType()), text, fetchedAt);
        } catch (Exception invalid) {
            throw new IllegalStateException("DTK order response invalid; original response retained");
        }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static Exchange httpExchange(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid timeout");
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, limit) -> {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).timeout(limit).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // Preserve the response body for diagnosis, including HTTP error bodies.
            return response.body();
        };
    }
}
