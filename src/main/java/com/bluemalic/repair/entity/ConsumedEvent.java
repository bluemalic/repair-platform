package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 消费幂等记录：同一消费组 + eventId 只处理一次。 */
@Data
@TableName("consumed_event")
public class ConsumedEvent {

    private Long id;

    private Long tenantId;

    private String consumerGroup;

    private String eventId;

    private LocalDateTime consumedAt;
}
