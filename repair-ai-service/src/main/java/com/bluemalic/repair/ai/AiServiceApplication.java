package com.bluemalic.repair.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 域独立进程（阶段 5 · ADR-036）。
 *
 * <p>它拥有三件事：**问数**（NL2SQL：生成 → SQL 网关 → 只读执行 → 结论）、
 * **受理/分诊 Agent**（多轮工具调用）、**维修知识库建议**（关键词检索 + 引用核对）。
 *
 * <p><b>为什么可以独立起</b>：本模块只注入 domain 的 mapper（工单表、类别、楼栋、报修码、知识库），
 * 不调用 ticket / identity 的任何 service —— 依赖方向是 `ai → domain/common`，
 * 所以工单域在不在这个进程里都行。
 *
 * <p><b>lite 形态不启本进程</b>：Bean 在单体里装配（app 依赖本模块的普通 jar）。
 */
@SpringBootApplication(scanBasePackages = {
        // 本模块自己的 ai 包（含 agent / knowledge 子包）
        "com.bluemalic.repair.ai",
        // 五个 controller（问数 / 学生受理 / 管理端知识库 / 工单分诊 / 维修建议）
        "com.bluemalic.repair.controller",
        "com.bluemalic.repair.service",
        "com.bluemalic.repair.converter",
        // common 的共享底座（租户路由 / 限流 / 数据权限 / 会话）
        "com.bluemalic.repair.config",
        "com.bluemalic.repair.interceptor",
        "com.bluemalic.repair.tenancy"
})
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
