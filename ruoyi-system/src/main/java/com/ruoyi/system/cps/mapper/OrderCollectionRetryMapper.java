package com.ruoyi.system.cps.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.cps.order.OrderCollectionRetry;

public interface OrderCollectionRetryMapper {
    int record(OrderCollectionRetry item);
    OrderCollectionRetry findByKey(@Param("key") String key);
    Long findReadyId();
    int claim(@Param("id") long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);
    OrderCollectionRetry findByLease(@Param("id") long id, @Param("token") String token);
    int complete(@Param("id") long id, @Param("token") String token);
    int fail(@Param("id") long id, @Param("token") String token,
             @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("errorCode") String errorCode);
}
