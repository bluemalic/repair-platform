package com.bluemalic.repair.ai;

import com.bluemalic.repair.tenancy.ReadOnlyAccountGranter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 AI 只读账号的授权接到 {@link ReadOnlyAccountGranter} 上（阶段 3.2）。
 *
 * <p>建库/迁移已经下沉到 {@code repair-common}，而只读账号的口令属于 ai 配置，
 * 只能留在 app / ai 服务里——这个适配器就是那唯一的连接点。
 * identity / platform / db-migrator 里没有它，那边的开通流程会跳过授权（下次 ai 服务启动时补齐）。
 */
@Component
@RequiredArgsConstructor
public class AiReadOnlyAccountGranter implements ReadOnlyAccountGranter {

    private final AiReadOnlyAccountBootstrap bootstrap;

    @Override
    public void grantOnDatabases(List<String> databases) {
        bootstrap.ensureAccountOnDatabases(databases);
    }
}
