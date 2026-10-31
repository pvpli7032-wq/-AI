package com.ruoyi.system.cps.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ruoyi.system.cps.rule.RewardApi.Award;

/** V701 storage only: no account writes. All calls require the sorted reward-user locks. */
public interface RewardProgressMapper {
    @Insert("INSERT INTO cps_reward_user_lock(user_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE user_id=user_id")
    int ensureUser(@Param("id") long id);
    @Select("SELECT user_id FROM cps_reward_user_lock WHERE user_id=#{id} FOR UPDATE")
    long lockUser(@Param("id") long id);
    @Select("SELECT payload FROM cps_reward_order_snapshot WHERE order_id=#{id}")
    String snapshot(@Param("id") long id);
    @Insert("INSERT INTO cps_reward_order_snapshot(order_id,platform,order_no,sub_order_no,buyer_id,inviter_id,payload) " +
        "VALUES(#{id},#{platform},#{order},#{sub},#{buyer},#{inviter},#{payload})")
    int insertSnapshot(@Param("id") long id, @Param("platform") String platform, @Param("order") String order,
        @Param("sub") String sub, @Param("buyer") long buyer, @Param("inviter") Long inviter, @Param("payload") String payload);
    @Insert("INSERT INTO cps_reward_order_state(order_id,buyer_id) VALUES(#{id},#{buyer})")
    int insertState(@Param("id") long id, @Param("buyer") long buyer);
    record State(boolean qualified, boolean valid, boolean closed, BigDecimal refundRatio, BigDecimal baselineRatio) {}
    @Select("SELECT qualified,valid,closed,refund_ratio,baseline_ratio FROM cps_reward_order_state WHERE order_id=#{id} FOR UPDATE")
    State state(@Param("id") long id);
    @Select("SELECT COUNT(*) FROM cps_reward_order_state WHERE buyer_id=#{buyer} AND qualified=1")
    long lifetimeCount(@Param("buyer") long buyer);
    @Select("SELECT COUNT(*) FROM cps_reward_order_state WHERE buyer_id=#{buyer} AND valid=1")
    long validCount(@Param("buyer") long buyer);
    @Select("SELECT COUNT(DISTINCT s.buyer_id) FROM cps_reward_order_snapshot s JOIN cps_reward_order_state t ON t.order_id=s.order_id " +
        "WHERE s.inviter_id=#{inviter} AND t.valid=1")
    long validInvitees(@Param("inviter") long inviter);
    @Update("UPDATE cps_reward_order_state SET qualified=1,valid=1,baseline_ratio=refund_ratio WHERE order_id=#{id}")
    int qualify(@Param("id") long id);
    @Update("UPDATE cps_reward_order_state SET refund_ratio=#{ratio},valid=(qualified=1 AND #{valid}),closed=(closed OR NOT #{valid}) WHERE order_id=#{id}")
    int refund(@Param("id") long id, @Param("ratio") BigDecimal ratio, @Param("valid") boolean valid);
    @Select("SELECT id,user_id,order_id,buyer_id,type,threshold,amount,recovered FROM cps_reward_award WHERE buyer_id=#{buyer} ORDER BY id")
    List<Award> awards(@Param("buyer") long buyer);
    @Select("SELECT COUNT(*) FROM cps_reward_award WHERE id=#{id}")
    long awardExists(@Param("id") String id);
    @Insert("INSERT INTO cps_reward_award(id,user_id,order_id,buyer_id,type,threshold,amount,recovered) " +
        "VALUES(#{id},#{userId},#{orderId},#{buyerId},#{type},#{threshold},#{amount},#{recovered})")
    int insertAward(Award award);
    @Update("UPDATE cps_reward_award SET recovered=#{amount} WHERE id=#{id}")
    int recover(@Param("id") String id, @Param("amount") BigDecimal amount);
    @Insert("INSERT INTO cps_reward_instruction(business_key,cause_order_id,payload) VALUES(#{key},#{cause},#{payload})")
    int instruction(@Param("key") String key, @Param("cause") long cause, @Param("payload") String payload);
    @Select("SELECT payload FROM cps_reward_instruction WHERE cause_order_id=#{id} ORDER BY sequence_id")
    List<String> instructions(@Param("id") long id);
}
