package com.ruoyi.system.cps.attribution;

import com.ruoyi.system.cps.mapper.QueryLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponQueryLogger {
    private final QueryLogMapper mapper;
    public CouponQueryLogger(QueryLogMapper mapper) { this.mapper = mapper; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(CouponQueryLog entry) {
        if (mapper.append(entry) != 1) throw new IllegalStateException("Unable to record coupon query");
    }
}
