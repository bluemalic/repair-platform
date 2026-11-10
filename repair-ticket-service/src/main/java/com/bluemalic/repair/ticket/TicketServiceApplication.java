package com.bluemalic.repair.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工单域独立进程（阶段 3.3 · ADR-024）。
 *
 * <p>负责：工单状态机 / 派单转派协作拆单合并 / 评价 / 资产 / 楼栋类别报修码排班 / 文件上传，
 * 以及**工单流转内核**（条件更新 + ticket_log + Outbox）。超时**执行**不在这里：
 * {@code TimeoutRegistrar} 的实现在 repair-timeout-api，本模块只依赖它（登记），不依赖超时进程。
 *
 * <p><b>与 identity 的边界</b>：本模块仍直接读 `sys_user`（18 处，派单校验与姓名回填）。
 * 那是有意的过渡态——阶段 3.3 先把表与状态机收进来，等 identity 的 `/internal` 契约稳定后
 * 再改 Feign（清单见 ADR-032 的 3.3 待办表）。
 *
 * <p><b>lite 形态不启本进程</b>：Bean 在单体里装配（app 依赖本模块的普通 jar）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.bluemalic.repair.ticket",
        // 本模块自己的 controller / service / converter（包名与单体一致）
        "com.bluemalic.repair.controller.student",
        "com.bluemalic.repair.controller.worker",
        "com.bluemalic.repair.controller.admin",
        "com.bluemalic.repair.service",
        "com.bluemalic.repair.converter",
        // common 的共享底座（Sa-Token / 租户路由 / 限流 / 数据权限）
        // 注意不要扫 com.bluemalic.repair.notification：那里是 notification 进程的入口类，
        // 通知写路径本身在 com.bluemalic.repair.service.impl 里，已被上面那行覆盖。
        "com.bluemalic.repair.config",
        "com.bluemalic.repair.interceptor",
        "com.bluemalic.repair.tenancy"
})
public class TicketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketServiceApplication.class, args);
    }
}
