package com.bluemalic.repair.job;

import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutScanner;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.bluemalic.repair.service.impl.TimeoutScanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底扫描的分片约定（阶段 4 · ADR-029）。
 *
 * <p>要钉住的只有一件事：**分片参数必须一路传到三个 backstop 查询**。
 * 传丢了不会报错，只会让"多实例各扫一片"变成"每个实例都扫全量"——
 * 学校一多就是重复扫库，而且没有任何现象能看出来。
 *
 * <p>真正的库过滤在 {@code TimeoutScannerImpl#scanTargets}（按 tenantId 取模），
 * 这里只验编排层不吞参数、三个节点都用同一个分片。
 */
class TimeoutScanServiceTest {

    private final TimeoutScanner timeoutScanner = mock(TimeoutScanner.class);

    private final TicketTimeoutHandler handler = mock(TicketTimeoutHandler.class);

    private final TimeoutScanService scanService = new TimeoutScanService(timeoutScanner, handler);

    @Test
    void 三个节点都按同一个分片扫描() {
        when(timeoutScanner.backstopScanEval(1, 3)).thenReturn(List.of(new DueTicket(4L, 40L)));
        when(timeoutScanner.backstopScanAccept(1, 3)).thenReturn(List.of(new DueTicket(7L, 70L)));
        when(timeoutScanner.backstopScanProcess(1, 3)).thenReturn(List.of());

        int handled = scanService.scan(1, 3);

        verify(timeoutScanner).backstopScanEval(1, 3);
        verify(timeoutScanner).backstopScanAccept(1, 3);
        verify(timeoutScanner).backstopScanProcess(1, 3);
        assertThat(handled).as("两个节点各处理 1 条").isEqualTo(2);
    }

    @Test
    void 单实例用零号分片扫全量() {
        when(timeoutScanner.backstopScanEval(0, 1)).thenReturn(List.of());
        when(timeoutScanner.backstopScanAccept(0, 1)).thenReturn(List.of());
        when(timeoutScanner.backstopScanProcess(0, 1)).thenReturn(List.of());

        assertThat(scanService.scan(0, 1)).isZero();

        verify(timeoutScanner).backstopScanEval(0, 1);
    }
}
