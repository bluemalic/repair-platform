package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationConsumerRecoveryTest {

    @Test
    void redeliveredEventStillResumesExternalDeliveryAfterInAppIdempotencyHit() {
        ConsumedEventMapper consumedEventMapper = mock(ConsumedEventMapper.class);
        NotificationDeliveryServiceImpl deliveryService = mock(NotificationDeliveryServiceImpl.class);
        TenantSchemaCatalog schemaCatalog = mock(TenantSchemaCatalog.class);
        when(schemaCatalog.routingKeyFor(1L)).thenReturn(TenantRoutingContext.CONTROL);
        when(consumedEventMapper.insert(any(ConsumedEvent.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("duplicate event"));

        NotificationConsumerService service = new NotificationConsumerService(
                consumedEventMapper, deliveryService, schemaCatalog, mock(PlatformTransactionManager.class));
        NotificationCommand command = new NotificationCommand(
                1L, 7L, "TICKET_ASSIGNED", "有新的工单", "请尽快接单", 9L);

        service.consume(1L, "test-group", "event-retry", command);
        service.consume(1L, "test-group", "event-retry", command);

        verify(deliveryService, times(1)).deliverInApp(any());
        verify(deliveryService, times(1)).scheduleExternal(any(), any());
        verify(deliveryService, times(2)).deliverExternal(any());
    }
}
