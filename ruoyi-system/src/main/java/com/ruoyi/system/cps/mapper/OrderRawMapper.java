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

    @Select("SELECT JSON_VALID(#{json})")
    boolean isValidJson(String json);
}
