package com.ruoyi.system.cps.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ruoyi.system.cps.rule.RuleDocuments.Version;

/** No UPDATE/DELETE of published versions or snapshots. */
@Mapper
public interface RuleVersionMapper {
    @Insert("INSERT INTO cps_rule_publication(document_id,revision,config,enabled,effective_at,created_by,safety_reference) VALUES(#{documentId},#{revision},#{config},#{enabled},CURRENT_TIMESTAMP(3),#{operator},#{safetyReference})")
    int append(@Param("documentId") long documentId, @Param("revision") long revision,
        @Param("config") String config, @Param("enabled") boolean enabled,
        @Param("operator") long operator, @Param("safetyReference") String safetyReference);

    @Select("SELECT v.id,v.document_id,v.revision,v.config,v.enabled,v.effective_at,v.created_by,v.safety_reference FROM cps_rule_publication v JOIN cps_rule_document d ON d.id=v.document_id WHERE d.platform=#{platform} AND v.effective_at <= #{paidAt} ORDER BY v.effective_at DESC,v.id DESC LIMIT 1")
    Version asOf(@Param("platform") String platform, @Param("paidAt") Instant paidAt);

    @Select("SELECT id,document_id,revision,config,enabled,effective_at,created_by,safety_reference FROM cps_rule_publication WHERE document_id=#{documentId} ORDER BY id DESC")
    List<Version> history(long documentId);

    @Select("SELECT snapshot FROM cps_rule_snapshot WHERE order_line_id=#{orderLineId}")
    String snapshot(long orderLineId);

    @Insert("INSERT INTO cps_rule_snapshot(order_line_id,buyer_user_id,version_id,snapshot) VALUES(#{orderLineId},#{buyerUserId},#{versionId},#{snapshot}) ON DUPLICATE KEY UPDATE order_line_id=order_line_id")
    int insertSnapshot(@Param("orderLineId") long orderLineId, @Param("buyerUserId") long buyerUserId,
        @Param("versionId") long versionId, @Param("snapshot") String snapshot);

    @Select("SELECT snapshot FROM cps_rule_snapshot WHERE order_line_id=#{orderLineId} FOR UPDATE")
    String lockedSnapshot(long orderLineId);
}
