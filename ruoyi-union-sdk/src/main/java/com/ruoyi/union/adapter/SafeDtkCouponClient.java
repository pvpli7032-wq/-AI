package com.ruoyi.union.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.net.ssl.HttpsURLConnection;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.client.DtkClient;
import com.ruoyi.union.dtk.api.constant.DtkApiConstant.RequestCommonParam;
import com.ruoyi.union.dtk.api.http.WebUtils;
import com.ruoyi.union.dtk.api.utils.JsonUtil;
import com.ruoyi.union.dtk.api.utils.RequiredCheck;
import com.ruoyi.union.dtk.api.utils.SignMd5Util;

/**
 * Dedicated coupon client that retains the existing DTK signature and DTO contract.
 * It never logs credentials, signed URLs, provider bodies or exception causes.
 * The old SDK clients remain untouched for their owning line to maintain.
 */
public final class SafeDtkCouponClient implements DtkClient {
    private static final String BASE = "https://openapi.dataoke.com/api";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String appKey;
    private final String appSecret;
    private final ConnectionFactory connections;

    @FunctionalInterface
    public interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public SafeDtkCouponClient(String appKey, String appSecret) {
        this(appKey, appSecret, 3000, 10000);
    }

    public SafeDtkCouponClient(String appKey, String appSecret, int connectMillis, int readMillis) {
        this(appKey, appSecret, connectMillis, readMillis,
            url -> WebUtils.getConnection(url, "GET", "application/json;charset=UTF-8"));
    }

    public SafeDtkCouponClient(String appKey, String appSecret, int connectMillis, int readMillis, ConnectionFactory factory) {
        this(appKey, appSecret, url -> {
            if (connectMillis < 1 || readMillis < 1 || connectMillis > 120000 || readMillis > 120000) {
                throw new IllegalArgumentException("HTTP timeouts out of range");
            }
            HttpURLConnection connection = factory.open(url);
            connection.setConnectTimeout(connectMillis);
            connection.setReadTimeout(readMillis);
            return connection;
        });
    }

    /** Transport seam for deterministic tests; request URLs still use the fixed DTK host. */
    public SafeDtkCouponClient(String appKey, String appSecret, ConnectionFactory connections) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw failure("UPSTREAM_NOT_CONFIGURED", "联盟服务尚未配置");
        }
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public <T> T execute(DtkApiRequest<T> request) {
        HttpURLConnection connection = null;
        try {
            validate(request);
            Map<String, String> parameters = new TreeMap<>(request.getTextParams());
            String nonce = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
            String timer = Long.toString(System.currentTimeMillis());
            parameters.remove(RequestCommonParam.APP_SECRET);
            parameters.put(RequestCommonParam.APP_KEY, appKey);
            parameters.put(RequestCommonParam.Nonce, nonce);
            parameters.put(RequestCommonParam.Timer, timer);
            String signature = SignMd5Util.sign("appKey=" + appKey + "&timer=" + timer + "&nonce=" + nonce, appSecret);
            if (signature == null) throw failure("UPSTREAM_FAILURE", "联盟请求签名失败");
            parameters.put(RequestCommonParam.SIGN, signature);

            URL url = URI.create(BASE + request.requestUrl() + "?" + query(parameters)).toURL();
            connection = connections.open(url);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(false);
            if (connection instanceof HttpsURLConnection secure) {
                // This path always verifies HTTPS, even if a legacy caller disabled the global SDK flag.
                secure.setSSLSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory());
                secure.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
            }
            if (connection.getConnectTimeout() <= 0 || connection.getReadTimeout() <= 0) {
                throw failure("UPSTREAM_NOT_CONFIGURED", "联盟请求必须配置有限超时");
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                throw failure("UPSTREAM_REDIRECT", "联盟服务返回了不支持的跳转");
            }
            if (status < 200 || status >= 300) throw failure("UPSTREAM_FAILURE", "联盟服务暂不可用");
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw oversized();
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_RESPONSE_BYTES) throw oversized();
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw failure("UPSTREAM_INVALID_RESPONSE", "联盟返回为空");
                return JsonUtil.jsonToPojoByTypeReference(output.toString(StandardCharsets.UTF_8), request.responseType());
            }
        } catch (UnionCouponException known) {
            throw known;
        } catch (SocketTimeoutException timedOut) {
            throw failure("UPSTREAM_TIMEOUT", "联盟服务响应超时，请稍后重试");
        } catch (Exception unavailable) {
            // The original cause may contain a signed URL or a response body. Do not retain it.
            throw failure("UPSTREAM_FAILURE", "联盟服务暂不可用");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void validate(DtkApiRequest<?> request) throws IllegalAccessException {
        if (request == null || request.requestUrl() == null
                || !request.requestUrl().matches("/[A-Za-z0-9/_-]+")
                || request.apiVersion() == null || request.apiVersion().isBlank()) {
            throw failure("INVALID_REQUEST", "联盟请求无效");
        }
        for (Class<?> type = request.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(RequiredCheck.class)) continue;
                field.setAccessible(true);
                Object value = field.get(request);
                if (value == null || value instanceof CharSequence text && text.toString().isBlank()) {
                    throw failure("INVALID_REQUEST", "联盟请求缺少必填字段");
                }
            }
        }
    }

    private String query(Map<String, String> parameters) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (parameter.getKey() == null || parameter.getValue() == null || parameter.getValue().isEmpty()) continue;
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static UnionCouponException oversized() {
        return failure("UPSTREAM_INVALID_RESPONSE", "联盟返回内容超过限制");
    }

    private static UnionCouponException failure(String code, String message) {
        return new UnionCouponException(code, message);
    }
}
