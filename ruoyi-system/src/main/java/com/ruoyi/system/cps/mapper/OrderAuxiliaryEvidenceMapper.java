package com.ruoyi.system.cps.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/** Read-only existing order evidence; automated penalties never update an order or account. */
public interface OrderAuxiliaryEvidenceMapper {
    @Select("SELECT id,order_id,sub_order_no,item_id,pid,attribution_token,order_amount,estimated_commission,"
        + "actual_commission,paid_time,received_time,settle_time,cps_paid_second,cps_paid_nano,"
        + "cps_settled_second,cps_settled_nano FROM sys_order_commission WHERE platform_type='tbk' "
        + "AND order_id=#{orderNo} AND sub_order_no=#{subOrderNo}")
    Map<String,Object> findTbk(Map<String,Object> key);

    @Insert("INSERT IGNORE INTO cps_punish_import(platform,file,operator,`lines`,automatic_key,raw_id,payload_text,"
        + "punish_status,violation_type,tk_adzone_id,tk_site_id) VALUES('tbk','DTK_SYNC',0,1,#{key},#{rawId},"
        + "#{payload},#{status},#{violation},#{adzone},#{site})")
    int appendAutomaticPenalty(Map<String,Object> row);
}
