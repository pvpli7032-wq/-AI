package com.ruoyi.system.cps.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderIngestMapper
{
    @Insert("INSERT INTO sys_order_commission (platform_type,order_id,sub_order_no,agent_user_id,order_amount) "
        + "VALUES (#{platform},#{orderNo},#{subOrderNo},NULL,#{paidAmount}) "
        + "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    int ensureLine(Map<String, Object> line);

    @Select("SELECT id,line_status,cps_status_second,cps_status_nano,cps_dispute_previous, "
        + "cps_paid_second,cps_paid_nano,cps_settled_second,cps_settled_nano FROM sys_order_commission "
        + "WHERE platform_type=#{platform} AND order_id=#{orderNo} AND sub_order_no=#{subOrderNo} FOR UPDATE")
    Map<String, Object> lockLine(Map<String, Object> line);

    @Update("UPDATE sys_order_commission SET line_status=#{status},raw_status=#{rawStatus},cps_status_second=#{seconds}, "
        + "cps_status_nano=#{nanos},cps_dispute_previous=#{previous}, "
        + "item_id=COALESCE(#{itemId},item_id),pid=COALESCE(#{pid},pid),attribution_token=COALESCE(#{token},attribution_token), "
        + "order_amount=COALESCE(#{paidAmount},order_amount),estimated_commission=COALESCE(#{estimated},estimated_commission), "
        + "actual_commission=COALESCE(#{actual},actual_commission),paid_time=COALESCE(#{paidAt},paid_time), "
        + "received_time=COALESCE(#{receivedAt},received_time),settle_time=COALESCE(#{settledAt},settle_time), "
        + "cps_paid_second=COALESCE(#{paidSecond},cps_paid_second),cps_paid_nano=COALESCE(#{paidNano},cps_paid_nano), "
        + "cps_settled_second=COALESCE(#{settledSecond},cps_settled_second),cps_settled_nano=COALESCE(#{settledNano},cps_settled_nano), "
        + "cps_fast_settlement=CASE WHEN #{status}='SETTLED' THEN #{suspicious} ELSE cps_fast_settlement END, "
        + "update_time=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
    int updateStatus(Map<String, Object> state);

    @Insert("INSERT INTO cps_order_ingest_exception(raw_id,order_line_id,event_key,reason,created_at) "
        + "VALUES(#{rawId},#{orderLineId},#{eventKey},#{reason},CURRENT_TIMESTAMP(3))")
    int appendException(Map<String, Object> exception);
}
