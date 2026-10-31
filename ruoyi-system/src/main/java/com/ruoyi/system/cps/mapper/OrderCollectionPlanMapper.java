package com.ruoyi.system.cps.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** UTC window checkpoints and planned runs, independent from funds and agent applications. */
public interface OrderCollectionPlanMapper {
    @Insert("INSERT IGNORE INTO cps_order_collection_checkpoint(platform,task_type,cycle_key,checkpoint_to) "
        + "VALUES(#{platform},#{type},#{cycle},#{initial})")
    int initialize(Map<String,Object> values);

    @Select("SELECT checkpoint_to FROM cps_order_collection_checkpoint "
        + "WHERE platform=#{platform} AND task_type=#{type} AND cycle_key=#{cycle}")
    java.time.LocalDateTime checkpoint(Map<String,Object> values);

    @Insert("INSERT INTO cps_order_collection_plan(run_id,platform,task_type,cycle_key,expected_to,window_from,window_to) "
        + "VALUES(#{runId},#{platform},#{type},#{cycle},#{expected},#{from},#{to})")
    int plan(Map<String,Object> values);

    @Update("UPDATE cps_order_collection_checkpoint c JOIN cps_order_collection_plan p "
        + "ON c.platform=p.platform AND c.task_type=p.task_type AND c.cycle_key=p.cycle_key "
        + "SET c.checkpoint_to=p.window_to WHERE p.run_id=#{runId} AND c.checkpoint_to=p.expected_to")
    int complete(String runId);

    @Select("SELECT COUNT(*) FROM cps_order_collection_checkpoint c JOIN cps_order_collection_plan p "
        + "ON c.platform=p.platform AND c.task_type=p.task_type AND c.cycle_key=p.cycle_key "
        + "WHERE p.run_id=#{runId} AND c.checkpoint_to>=p.window_to")
    int alreadyCompleted(String runId);

    @Insert("INSERT INTO cps_order_collection_rejection(run_id,platform,reason,created_at) "
        + "VALUES(#{runId},#{platform},'PID_OR_SITE_NOT_OWNED',CURRENT_TIMESTAMP(3))")
    int auditRejected(Map<String,Object> values);
}
