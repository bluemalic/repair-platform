package com.bluemalic.repair.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数归一化的边界。
 *
 * <p>它是纯函数，本来靠列表接口的测试间接覆盖也够。单独立一个的原因：这段逻辑现在被
 * **五个** Service 共用（工单 / 维修工 / 报修码 / 通知 / 学生），而它此前在四处各复制了一份——
 * 把边界钉住的成本比"以后某次改动只改到其中一份"低得多。
 */
class PagingTest {

    @Test
    void clampsToValidRange() {
        assertThat(Paging.clamp(0)).isEqualTo(1);
        assertThat(Paging.clamp(-5)).isEqualTo(1);
        assertThat(Paging.clamp(Long.MIN_VALUE)).isEqualTo(1);

        // 合法区间原样返回：不能把正常值也改掉
        assertThat(Paging.clamp(1)).isEqualTo(1);
        assertThat(Paging.clamp(20)).isEqualTo(20);
        assertThat(Paging.clamp(Paging.MAX_PAGE_SIZE)).isEqualTo(Paging.MAX_PAGE_SIZE);

        // 上限封顶：这条同时防住"前端传 pageSize=100000 把整库拉出来"
        assertThat(Paging.clamp(Paging.MAX_PAGE_SIZE + 1)).isEqualTo(Paging.MAX_PAGE_SIZE);
        assertThat(Paging.clamp(Long.MAX_VALUE)).isEqualTo(Paging.MAX_PAGE_SIZE);
    }
}
