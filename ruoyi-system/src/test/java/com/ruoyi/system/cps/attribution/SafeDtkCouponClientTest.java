package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import com.ruoyi.union.adapter.SafeDtkCouponClient;
import com.ruoyi.union.adapter.UnionCouponException;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdLinkAnalysisRequest;
import com.ruoyi.union.dtk.api.utils.SignMd5Util;

public class SafeDtkCouponClientTest {
    @Test public void signsExistingProtocolAndUsesOneEncodingWithoutFollowingRedirects() {
        final FakeConnection[] opened = new FakeConnection[1];
        SafeDtkCouponClient client = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            opened[0] = new FakeConnection(url, 200, "{\"code\":0,\"data\":{\"skuId\":\"123\"}}");
            return opened[0];
        });
        DtkJdLinkAnalysisRequest request = request();
        assertEquals("123", client.execute(request).getData().getSkuId());
        FakeConnection connection = opened[0];
        assertEquals("https", connection.getURL().getProtocol());
        assertEquals("openapi.dataoke.com", connection.getURL().getHost());
        assertEquals("/api/dels/jd/kit/parseUrl", connection.getURL().getPath());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
        Map<String, String> parameters = parameters(connection.getURL());
        assertEquals(request.getUrl(), parameters.get("url"));
        assertEquals("test-key", parameters.get("appKey"));
        assertFalse(connection.getURL().toString().contains("test-secret"));
        assertEquals(SignMd5Util.sign("appKey=test-key&timer=" + parameters.get("timer")
                + "&nonce=" + parameters.get("nonce"), "test-secret"), parameters.get("signRan"));
    }

    @Test public void rejectsRedirectWithoutReadingPotentiallySensitiveBody() {
        FakeConnection[] opened = new FakeConnection[1];
        SafeDtkCouponClient client = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            opened[0] = new FakeConnection(url, 302, "secret-body");
            return opened[0];
        });
        UnionCouponException error = assertThrows(UnionCouponException.class, () -> client.execute(request()));
        assertEquals("UPSTREAM_REDIRECT", error.code());
        assertFalse(opened[0].read);
        assertTrue(opened[0].disconnected);
        assertNull(error.getCause());
    }

    @Test public void rejectsOversizedDeclaredAndStreamedBodies() {
        SafeDtkCouponClient declared = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            FakeConnection connection = new FakeConnection(url, 200, "ignored");
            connection.length = 2L * 1024 * 1024;
            return connection;
        });
        assertEquals("UPSTREAM_INVALID_RESPONSE", assertThrows(UnionCouponException.class,
                () -> declared.execute(request())).code());
        SafeDtkCouponClient streamed = new SafeDtkCouponClient("test-key", "test-secret",
                url -> new FakeConnection(url, 200, "x".repeat(1024 * 1024 + 1)));
        assertEquals("UPSTREAM_INVALID_RESPONSE", assertThrows(UnionCouponException.class,
                () -> streamed.execute(request())).code());
    }

    @Test public void masksTransportAndJsonFailuresAndClassifiesTimeout() {
        SafeDtkCouponClient broken = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            throw new IOException("signed URL SECRET");
        });
        UnionCouponException error = assertThrows(UnionCouponException.class, () -> broken.execute(request()));
        assertEquals("UPSTREAM_FAILURE", error.code());
        assertFalse(error.toString().contains("SECRET"));
        assertNull(error.getCause());
        SafeDtkCouponClient timeout = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            throw new SocketTimeoutException("SECRET");
        });
        assertEquals("UPSTREAM_TIMEOUT", assertThrows(UnionCouponException.class,
                () -> timeout.execute(request())).code());
        SafeDtkCouponClient json = new SafeDtkCouponClient("test-key", "test-secret",
                url -> new FakeConnection(url, 200, "SECRET malformed body"));
        assertNull(assertThrows(UnionCouponException.class, () -> json.execute(request())).getCause());
    }

    @Test public void validatesRequiredParametersBeforeOpeningConnection() {
        SafeDtkCouponClient client = new SafeDtkCouponClient("test-key", "test-secret", url -> {
            fail("Invalid request must not open a connection");
            return null;
        });
        assertEquals("INVALID_REQUEST", assertThrows(UnionCouponException.class,
                () -> client.execute(new DtkJdLinkAnalysisRequest())).code());
    }

    private static DtkJdLinkAnalysisRequest request() {
        DtkJdLinkAnalysisRequest request = new DtkJdLinkAnalysisRequest();
        request.setUrl("https://u.jd.com/test?a=1&b=中文");
        return request;
    }

    private static Map<String, String> parameters(URL url) {
        Map<String, String> result = new HashMap<>();
        for (String pair : url.getQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final String body;
        boolean disconnected;
        boolean read;
        long length = -1;
        FakeConnection(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.body = body;
            setConnectTimeout(1000);
            setReadTimeout(1000);
        }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() {
            read = true;
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
