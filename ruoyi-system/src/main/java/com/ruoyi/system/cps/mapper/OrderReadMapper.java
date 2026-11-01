package com.ruoyi.system.cps.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Read-only order view; ownership is resolved only through current attribution. */
public interface OrderReadMapper {
    List<Map<String, Object>> listAdmin(@Param("platform") String platform, @Param("status") String status, @Param("method") String method);
    Map<String, Object> findAdmin(@Param("id") Long id);
    List<Map<String, Object>> attributionHistory(@Param("id") Long id);
    List<Map<String, Object>> statusHistory(@Param("id") Long id);
    List<Map<String, Object>> listForUser(@Param("userId") Long userId);
}
