## 改了什么

<!-- 一句话说明这个 PR 解决的问题，不要只列文件名 -->

## 验证

- [ ] `mvn -B clean package` 通过（含 JaCoCo 门槛）
- [ ] 改了前端：对应工程 `npm run typecheck && npm run build` 通过
- [ ] 新增/修改的测试覆盖了变更点
- [ ] `scripts/check-docs.sh` 没有需要处理的 warning

## 文档同步（AGENTS.md 第 8 节）

- [ ] 表结构 / 字段 / 索引 → 迁移脚本 + `docs/02`
- [ ] 接口 / 错误码 / 权限码 → `docs/03`（+ `R__seed.sql`）
- [ ] 部署 / 环境变量 / 内存 → `docs/05` + `docs/10` + `.env.example`
- [ ] 技术选型 / 架构取舍 → `docs/04` 新增 ADR
- [ ] 模块职责 / 数据流 → `docs/06`
- [ ] 进度 / 测试数 → `README` 或 `docs/00`（易变数字只写一处）
- [ ] 配置默认值 / 注释 → 对应 `application*.yml` / `docker-compose*.yml` / `nginx.conf`

未同步的文档及原因：
