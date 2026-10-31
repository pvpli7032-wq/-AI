package com.ruoyi.system.cps.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderEventMapper
{
    @Select("SELECT COUNT(*) FROM cps_order_event WHERE event_key=#{key}")
    int countByKey(String key);

    @Select("SELECT line_snapshot FROM cps_order_event_outbox WHERE event_key=#{key}")
    String selectSnapshot(String key);

    /** First refund freezes its pre-refund commission basis, including an explicitly missing basis. */
    @Select("SELECT o.line_snapshot FROM cps_order_event_outbox o JOIN cps_order_event e ON e.id=o.event_id "
        + "WHERE e.order_line_id=#{orderId} AND e.to_status IN ('PARTIAL_REFUND','FULL_REFUND') ORDER BY o.id LIMIT 1")
    String selectFirstRefundSnapshot(long orderId);

    @Insert("INSERT INTO cps_order_event(event_key,order_line_id,from_status,to_status,previous_status,source,raw_id,created_at) "
        + "VALUES(#{eventKey},#{orderLineId},#{fromStatus},#{toStatus},#{previous},#{source},#{rawId},#{occurredAt})")
    @Options(useGeneratedKeys=true,keyProperty="eventId")
    int append(Map<String, Object> event);

    @Insert("INSERT INTO cps_order_event_outbox(event_id,event_key,line_snapshot,occurred_second,occurred_nano,created_at) VALUES(#{eventId},#{eventKey}, "
        + "JSON_OBJECT('platform',#{platform},'orderNo',#{orderNo},'subOrderNo',#{subOrderNo},'itemId',#{itemId}, "
        + "'pid',#{pid},'attributionToken',#{token},'paidAmount',#{paidAmount},'estimatedCommission',#{estimated}, "
        + "'actualCommission',#{actual},'recoveredAmount',#{recovered},'refundBasisAmount',#{refundBasis},'rawStatus',#{rawStatus},'paidAt',#{paidAtText},'receivedAt',#{receivedAtText},'settledAt',#{settledAtText}),#{seconds},#{nanos},CURRENT_TIMESTAMP(3))")
    int enqueue(Map<String, Object> event);

    @Select("SELECT o.id,o.event_key,o.line_snapshot,o.attempt_count,o.attribution_done,o.settlement_done,o.occurred_second,o.occurred_nano,e.order_line_id,e.from_status,e.to_status,e.previous_status,e.source,e.raw_id,e.created_at,r.payload_text AS raw_response_json "
        + "FROM cps_order_event_outbox o JOIN cps_order_event e ON e.id=o.event_id LEFT JOIN cps_order_raw r ON r.id=e.raw_id "
        + "WHERE (o.status='PENDING' OR (o.status='PROCESSING' AND o.lease_until < CURRENT_TIMESTAMP(3))) "
        + "AND NOT EXISTS (SELECT 1 FROM cps_order_event_outbox prior JOIN cps_order_event pe ON pe.id=prior.event_id "
        + "WHERE pe.order_line_id=e.order_line_id AND prior.id<o.id AND prior.status <> 'DISPATCHED') "
        + "ORDER BY o.id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Map<String,Object>> lockPending(int limit);

    @Update("UPDATE cps_order_event_outbox SET status='PROCESSING',claim_token=#{token}, "
        + "lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(3)),attempt_count=attempt_count+1 WHERE id=#{id}")
    int claim(Map<String,Object> claim);

    @Update("UPDATE cps_order_event_outbox SET status='DISPATCHED',claim_token=NULL,lease_until=NULL "
        + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token} AND lease_until >= CURRENT_TIMESTAMP(3) "
        + "AND attribution_done=TRUE AND settlement_done=TRUE")
    int acknowledge(Map<String,Object> claim);

    @Update("UPDATE cps_order_event_outbox SET attribution_done=TRUE "
        + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token} AND lease_until >= CURRENT_TIMESTAMP(3)")
    int markAttributionDone(Map<String,Object> claim);

    @Update("UPDATE cps_order_event_outbox SET settlement_done=TRUE "
        + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token} AND lease_until >= CURRENT_TIMESTAMP(3) AND attribution_done=TRUE")
    int markSettlementDone(Map<String,Object> claim);

    @Update("UPDATE cps_order_event_outbox SET status='PENDING',claim_token=NULL,lease_until=NULL "
        + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token} AND lease_until >= CURRENT_TIMESTAMP(3)")
    int release(Map<String,Object> claim);
}
