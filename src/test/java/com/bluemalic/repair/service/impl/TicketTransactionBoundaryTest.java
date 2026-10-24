package com.bluemalic.repair.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务边界的回归守卫：工单命令必须事务化；超时处理器不能加类级事务注解
 * （它要先 pin 租户库，再用 TransactionTemplate 开事务）。
 */
class TicketTransactionBoundaryTest {

    @Test
    void ticketCommandServicesAreTransactional() {
        assertThat(TicketFlowServiceImpl.class.getAnnotation(Transactional.class))
                .as("工单流转的状态、日志、通知必须在同一个事务里")
                .isNotNull();
        assertThat(TicketAssignmentServiceImpl.class.getAnnotation(Transactional.class))
                .as("派单/转派/协同/拆单必须在同一个事务里")
                .isNotNull();
    }

    @Test
    void timeoutHandlerDoesNotUseClassLevelTransaction() {
        assertThat(TicketTimeoutHandler.class.getAnnotation(Transactional.class))
                .as("类级事务会在设置租户路由键之前绑定连接，可能静默连到平台库")
                .isNull();
    }
}
