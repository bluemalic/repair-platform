package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.NotificationDelivery;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 通知投递记录 Mapper（阶段 4）。
 *
 * <p>这张表**不在数据权限拦截器的名单里**（只有 ticket / notification 在），
 * 所以按租户查时必须显式写 {@code tenant_id} —— 见 AGENTS §5.6。
 */
public interface NotificationDeliveryMapper extends BaseMapper<NotificationDelivery> {

    /** Atomically claims a due row and stores the lease expiry in next_retry_at. */
    @Update("UPDATE notification_delivery " +
            "SET status = 'SENDING', next_retry_at = #{leaseUntil}, update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = #{expectedStatus} " +
            "AND next_retry_at = #{expectedNextRetryAt} AND next_retry_at <= #{now}")
    int claimDue(@Param("id") Long id,
                 @Param("tenantId") Long tenantId,
                 @Param("expectedStatus") String expectedStatus,
                 @Param("expectedNextRetryAt") LocalDateTime expectedNextRetryAt,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Fenced completion: an expired worker cannot overwrite a later claimant's result. */
    @Update("UPDATE notification_delivery " +
            "SET status = #{status}, retry_count = #{retryCount}, last_error = #{lastError}, " +
            "next_retry_at = #{nextRetryAt}, update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'SENDING' " +
            "AND next_retry_at = #{leaseUntil}")
    int completeClaim(@Param("id") Long id,
                      @Param("tenantId") Long tenantId,
                      @Param("leaseUntil") LocalDateTime leaseUntil,
                      @Param("status") String status,
                      @Param("retryCount") Integer retryCount,
                      @Param("lastError") String lastError,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
