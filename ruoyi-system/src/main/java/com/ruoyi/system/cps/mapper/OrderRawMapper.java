package com.ruoyi.system.cps.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface OrderRawMapper
{
    @Insert("INSERT INTO cps_order_raw (platform,payload,payload_text,fetched_at,content_hash) "
        + "VALUES (#{platform}, IF(JSON_VALID(#{raw}), #{raw}, JSON_QUOTE(#{raw})), #{raw}, #{fetchedAt}, #{hash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int append(Map<String, Object> raw);

    @Insert("INSERT INTO cps_order_replay_input(raw_id,input_json,created_at) VALUES(#{rawId},#{input},CURRENT_TIMESTAMP(3))")
    int appendInput(Map<String,Object> input);

    @Select("SELECT input_json FROM cps_order_replay_input WHERE raw_id=#{rawId}")
    String selectInput(long rawId);

    @Select("SELECT JSON_VALID(#{json})")
    boolean isValidJson(String json);
}
