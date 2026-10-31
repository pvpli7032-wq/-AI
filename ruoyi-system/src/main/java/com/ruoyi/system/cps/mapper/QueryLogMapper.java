package com.ruoyi.system.cps.mapper;

import com.ruoyi.system.cps.attribution.CouponQueryLog;
import org.apache.ibatis.annotations.Insert;

public interface QueryLogMapper {
    @Insert("INSERT INTO cps_query_log(user_id,platform,raw_hash,item_id,result,commission_rate,shown_rebate,pid,created_at) "
        + "VALUES(#{userId},#{platform},#{rawHash},#{itemId},#{result},#{commissionRate},#{shownRebate},#{pid},#{createdAt})")
    int append(CouponQueryLog entry);
}
