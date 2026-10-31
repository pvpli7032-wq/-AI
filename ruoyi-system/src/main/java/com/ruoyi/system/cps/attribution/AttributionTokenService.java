package com.ruoyi.system.cps.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ruoyi.union.adapter.Platform;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Opaque values issued only for the authenticated user, never decoded as user IDs. */
@Service
public class AttributionTokenService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final AttributionSettings settings;
    public AttributionTokenService(JdbcTemplate jdbc) { this.jdbc=jdbc; this.settings=new AttributionSettings(jdbc); }
    public String issue(Platform platform, String itemId, Long userId) {
        Objects.requireNonNull(platform); AttributionData.text(itemId,128,"商品标识");
        AttributionData.requireUser(jdbc,userId);
        String token=UUID.randomUUID().toString().replace("-","");
        Instant issuedAt=AttributionData.now(jdbc);
        jdbc.update("INSERT INTO cps_attribution_token(token,platform,item_id,user_id,issued_at,expires_at) VALUES (?,?,?,?,?,?)",token,platform.name(),itemId,userId,AttributionData.timestamp(issuedAt),AttributionData.timestamp(issuedAt.plus(settings.leaseDuration())));
        return token;
    }
    Long resolve(Platform platform, String itemId, String raw, Instant paidAt) {
        String token=normalize(platform,raw);
        if (token==null || itemId==null || paidAt==null) return null;
        List<Long> users=jdbc.queryForList("SELECT t.user_id FROM cps_attribution_token t JOIN sys_user u ON u.user_id=t.user_id WHERE t.token=? AND t.platform=? AND BINARY t.item_id=BINARY ? AND t.issued_at<=? AND t.expires_at>? AND u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_wechat_user w WHERE w.user_id=t.user_id) FOR SHARE",
            Long.class,token,platform.name(),itemId,AttributionData.timestamp(paidAt),AttributionData.timestamp(paidAt));
        return users.size()==1 ? users.get(0) : null;
    }
    /** Retain expired evidence for the configured unclaimed-order period to allow delayed sync. */
    public int cleanupExpired(int limit) {
        if (limit <= 0 || limit > 10000) throw new IllegalArgumentException("invalid cleanup batch");
        Instant cutoff = AttributionData.now(jdbc).minus(settings.unclaimedDuration());
        return jdbc.update("DELETE FROM cps_attribution_token WHERE expires_at IS NOT NULL AND expires_at<? ORDER BY expires_at LIMIT ?",AttributionData.timestamp(cutoff),limit);
    }
    static String normalize(Platform platform,String raw) {
        if(raw==null || raw.length()>255) return null;
        String token=raw;
        if(platform==Platform.PDD && raw.startsWith("{")) {
            try {
                JsonNode json=JSON.readTree(raw);
                if(!json.isObject() || json.size()!=1 || !json.has("uid") || !json.get("uid").isTextual()) return null;
                token=json.get("uid").textValue();
            } catch(Exception invalid) { return null; }
        }
        return token.matches("[0-9a-f]{32}") ? token : null;
    }
}
