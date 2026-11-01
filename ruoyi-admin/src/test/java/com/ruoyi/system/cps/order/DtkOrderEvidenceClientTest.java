package com.ruoyi.system.cps.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetOrderDetailsRequest;
import static org.junit.Assert.*;

public class DtkOrderEvidenceClientTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Test public void preservesExactResponseBeforeDecodingAndDoesNotShareCredentials() {
        List<String> urls = new ArrayList<>(), retained = new ArrayList<>();
        String text = "{ \"code\": 0, \"data\": {\"has_next\":false} }";
        for (String key : List.of("fake-key-a", "fake-key-b")) {
            var client = new DtkOrderEvidenceClient(key, "fake-secret", Duration.ofSeconds(3), (uri, timeout) -> {
                assertEquals("openapi.dataoke.com", uri.getHost());
                assertEquals("https", uri.getScheme());
                urls.add(uri.toString()); return text;
            }, Clock.fixed(NOW, ZoneOffset.UTC));
            var response = client.execute(new DtkGetOrderDetailsRequest(), (raw, fetched) -> {
                assertEquals(NOW, fetched); retained.add(raw);
            });
            assertEquals(text, response.rawJson());
            assertEquals(NOW, response.fetchedAt());
            assertNotNull(response.data().getData());
        }
        assertTrue(urls.get(0).contains("appKey=fake-key-a"));
        assertTrue(urls.get(1).contains("appKey=fake-key-b"));
        assertFalse(urls.get(0).contains("fake-secret"));
        assertEquals(List.of(text, text), retained);
    }
    @Test public void invalidResponsesAreRetainedAndSecretBearingExceptionsAreSuppressed() {
        List<String> retained = new ArrayList<>();
        var malformed = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1),
            (uri, timeout) -> "<html>upstream error</html>", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> malformed.execute(new DtkGetOrderDetailsRequest(),
            (raw, time) -> retained.add(raw)));
        assertEquals(List.of("<html>upstream error</html>"), retained);
        var broken = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1),
            (uri, timeout) -> { throw new RuntimeException(uri + " private-token"); }, Clock.fixed(NOW, ZoneOffset.UTC));
        Exception failure = assertThrows(IllegalStateException.class, () -> broken.execute(new DtkGetOrderDetailsRequest(),
            (raw, time) -> fail("No response should be manufactured")));
        assertFalse(failure.toString().contains("private-token"));
        assertFalse(failure.toString().contains("appKey"));
        assertNull(failure.getCause());
    }
    @Test public void cannotDecodeBeforeRawHasBeenCommitted() {
        var client = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1),
            (uri, timeout) -> "{}", Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeException expected = new IllegalStateException("storage unavailable");
        assertSame(expected, assertThrows(IllegalStateException.class, () -> client.execute(new DtkGetOrderDetailsRequest(),
            (raw, time) -> { throw expected; })));
    }
}
