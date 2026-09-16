# AGENTS.md — 项目约定

> 本文件是这个项目的**硬性约定**，任何 AI 助手在本仓库生成或修改代码前都必须先读它。
> 与 `docs/` 里的设计文档冲突时，以 `docs/` 为准；两者都没写到的地方，**先问，不要自己决定**。

---

## 1. 项目是什么

高校后勤报修 SaaS 平台（含 AI 数据助手）。三个角色：学生报修、维修工接单上门、后勤派单调度，并支持用自然语言直接问数据。

**目标**：这是要长期维护、需要向他人交代的**工程规范型项目**，不是练手 demo。宁可少做一个功能，也不留下"说不清为什么这么写"的代码。

---

## 2. 动手前先读这几份

| 文档 | 什么时候读 |
|---|---|
| **`docs/00-项目全景.md`** | **第一次接触项目 / 想知道现在做到哪了** —— 进度、路线图、全链路图、文档索引 |
| `docs/01-需求文档.md` | 每次要加功能前 —— 确认这事在"必做"清单里 |
| `docs/02-数据库设计.md` | 涉及任何表结构、字段、索引时 |
| `docs/03-接口规范.md` | 写任何 Controller / 接口时 |
| `docs/04-架构决策记录.md` | 想换技术方案时 —— 先看有没有已经做过的决策 |

---

## 3. 技术栈（**不要擅自更换或新增**）

| 用途 | 选型 | 备注 |
|---|---|---|
| 框架 | Spring Boot **3.5.16** + Java **17** | 不要升到 4.x |
| ORM | MyBatis-Plus | 不要引入 JPA / Hibernate |
| 数据库 | MySQL 8（本机端口 **13306**） | 不要连 3306，那是本机原生 MySQL |
| 缓存 / 队列 | Redis 7（本机 **6379**，库号 **0**） | 延迟队列用它，不要引 Kafka / RabbitMQ |
| 对象存储 | MinIO（S3 协议） | 不要直接依赖 OSS SDK，只依赖 S3 API |
| 鉴权 | Sa-Token | 不要引入 Spring Security |
| 接口文档 | Knife4j（OpenAPI 3） | 每个接口都要有注解 |
| AI | LangChain4j + DeepSeek API | Key 只从环境变量读 |
| 测试 | JUnit 5 + Mockito | |
| 管理端前端 | Vue 3 + Vite + TypeScript + Element Plus + ECharts | 在 `web-admin/`，自带 package.json |
| 移动端前端 | uni-app（Vue 3 语法，先编译 H5） | 在 `miniapp-h5/`，学生端与维修工端同一工程按角色路由 |
| 部署 | Docker + Docker Compose + Nginx | |
| CI | GitHub Actions | 前端 CI 独立于后端，按路径触发 |

> **"不要引入 Spring Security"指的是一整套安全框架**（过滤器链 + 认证授权 + 自动装配），不是指它的加密工具包。
> 需要 BCrypt 时可以用 `org.springframework.security:spring-security-crypto`——它只含 BCrypt / Argon2 / PBKDF2，
> 没有过滤器链、没有自动装配，版本由 Spring Boot 统一管理；鉴权仍然走 Sa-Token，两者不冲突。

**要新增任何依赖，先说明理由并得到确认。**（前端同理：往 `package.json` 加运行时依赖也要先说明。）

---

## 3.1 前端的仓库边界（决定：先单仓，保留将来拆分路径，见 ADR-007）

前端暂时与后端同仓库（`web-admin/`、`miniapp-h5/`），但**必须守住下面四条**，否则将来拆分就从"搬运"变成"重写"：

1. **两个前端各是独立工程**：各自的 `package.json` + lockfile，**不要在仓库根目录搞 npm workspace 把它们连起来**
2. **不互相引用源码**：前端不 import 后端代码；后端也不引用前端源码。唯一连接点是 nginx 挂载 `dist` 与 `docs/03` 的接口契约
3. **绝不把前端产物放进后端 jar**（`src/main/resources/static`）——它会让版本一起发、镜像变大、拆分要动 Java 代码
4. **后端构建不依赖前端**：后端 Dockerfile / 后端 CI 里不出现 Node；前端构建失败不影响后端发布

此外：接口契约以 `docs/03` 为准，前端不去"读后端代码猜接口"；契约变更先改文档再改两端。

---

## 4. 包结构

```
com.bluemalic.repair
├── common/        统一返回体、业务异常、错误码、常量、工具
├── config/        配置类（MyBatis-Plus、Sa-Token、Redis、Knife4j、线程池）
├── controller/
│   ├── student/   学生端接口
│   ├── worker/    维修工端接口
│   └── admin/     后勤管理端接口
├── service/       接口
│   └── impl/      实现
├── mapper/        MyBatis-Plus Mapper
├── entity/        数据库实体，与表一一对应
├── dto/           入参
├── vo/            出参
├── converter/     DTO / VO / Entity 之间的转换
├── interceptor/   数据权限、鉴权拦截器
├── ai/            Schema 检索、SQL 生成、SQL 安全网关（M4）
└── job/           定时任务
```

前端工程（仓库根目录，与后端平级）：

```
web-admin/     后勤管理端（Vue 3 + Element Plus + ECharts），Vite 构建产物 dist/ 由 nginx 挂载
miniapp-h5/    学生 / 维修工端（uni-app，先编译 H5），产物 dist/ 由 nginx 挂载
```

---

## 5. 编码约定（硬性）

1. **统一返回体**：所有 Controller 返回 `Result<T>`，字段 `code / message / data / traceId`。
2. **错误码集中管理**：只能从 `docs/03-接口规范.md` 的错误码表里取，**不要新造**。分段规则：1xxxx 通用、2xxxx 工单、3xxxx 权限、4xxxx AI、5xxxx 文件。
3. **异常**：业务问题抛自定义业务异常，由全局异常处理器 `@RestControllerAdvice` 兜底；**不要在各处 try-catch 后 return 错误码**。
4. **对象分离**：入参用 `dto`，出参用 `vo`，数据库实体用 `entity`，**禁止把 entity 直接当接口出入参**。转换逻辑放 `converter`。
5. **Service 里不写 SQL**。所有 SQL 放 Mapper / XML。
6. **数据权限不手写**。学生 / 维修工的数据范围由 MyBatis 拦截器统一注入条件，**任何 Mapper 里都不许手写 `student_id = ?` 之类的过滤条件**。
7. **参数校验**：用 `@Valid` + `jakarta.validation` 注解，不要手写 if 判空。
8. **日志**：关键业务节点（提交、派单、接单、核销、AI 查询）必须打日志；traceId 由过滤器注入 MDC 贯穿全链路；**手机号、密码等敏感信息不打日志**。
9. **配置**：全部走 `${ENV_VAR:默认值}` 占位；**任何密码 / Key 都不许硬编码进代码**。
10. **注释**：只在"代码本身表达不了"的地方写，比如为什么选这个方案、有什么坑。不要写"这里是查询工单"这种复述。

---

## 6. 数据库约定

- 表名、字段名一律 `snake_case`；表名不加前缀。
- 主键：`bigint`，**雪花 ID**，不用自增。
- 审计字段：`create_time`、`update_time`；业务主表带 `deleted`（逻辑删除 0/1），关联表与追加型表是否带见 `docs/02-数据库设计.md`。
- **不使用物理外键**，由应用层保证。
- 状态字段用 `tinyint` + 枚举类；状态值从 10 开始按 10 递增（留插入空间）。
- 索引命名：普通索引 `idx_字段`、唯一索引 `uk_字段`（MySQL 索引名在表内唯一，不强制加表名前缀；多字段按顺序拼接，如 `idx_tenant_status`）。
- **改表结构必须同步改两处**：`docs/02-数据库设计.md` 和 `docs/schema.sql`。

---

## 7. 接口约定

- 路径按端前缀：`/api/student/**`、`/api/worker/**`、`/api/admin/**`。
- 资源用复数名词，动作用子路径：`POST /api/worker/tickets/{id}/accept`。
- 每个接口都要有 Knife4j 注解（`@Tag` / `@Operation` / `@Parameter`）。
- 分页统一返回 `total / pageNum / pageSize / pages / list`。
- **报修码是跨端抽象**：`GET /api/tickets/by-code/{code}` 是学生扫码报修和维修工扫码到场共用的接口，不要为某一端单开一个。

---

## 8. 测试与提交

- 单测优先覆盖三类：**状态机流转、幂等逻辑、数据权限过滤**。
- 提交前必须 `mvn -B clean package` 通过；**改了前端**则对应工程 `npm run build` 也要通过。
- Commit 用 Conventional Commits：`feat(order): ...` / `fix(auth): ...` / `docs(db): ...`。
  - 前端 scope 固定用 `admin`（管理端）与 `h5`（学生/维修工端），例如 `feat(admin): 工单列表页`；
    将来按路径切分历史时，这两个 scope 就是天然的分界线。
- 每个功能走分支 + PR，不要直接推 main。
- 前端不在后端 CI 里构建（见 3.1 第 4 条）：前端 CI 由 `.github/workflows/frontend-ci.yml` 按路径触发。

---

## 9. 明确不要做的事

| 不要做 | 原因 |
|---|---|
| 引入 Spring Cloud / Nacos / 微服务 | 单体里能讲透的远多于半成品微服务 |
| 引入 Kafka / RabbitMQ | 超时升级用 Redis 延迟队列，通知用站内通知，用不上 |
| 接微信授权登录（`wx.login`） | 只有小程序有，H5 没有，会破坏跨端 |
| 用微信订阅消息 / 短信 / 邮件做通知 | 用站内通知，零成本且跨端通用 |
| 做失物招领、活动报名 | 与维修无关，只会稀释主线 |
| 在 Mapper 里手写数据权限条件 | 必须走拦截器，避免漏写造成越权 |
| 把密码 / API Key 写进代码或提交 `.env` | 一旦进 Git 历史就删不干净 |
| 把前端产物塞进后端 jar（`resources/static`） | 前后端版本会被绑在一起发；将来拆分要动 Java 代码（见 3.1） |
| 在仓库根目录用 npm workspace 连起两个前端 | 破坏"各是独立工程"的边界，将来拆分成解绑工作（见 3.1） |
| 留下 `TODO` 占位就交付 | 要么写完，要么明确标注未做 |
| 未经确认新增第三方依赖 | 任何新依赖都要能解释为什么需要它 |

---

## 10. 与 AI 协作的要求

1. **先看文档再写码**。不确定某个设计为什么这样时，去 `docs/04-架构决策记录.md` 找，找不到就问。
2. **小步改**。一次只做一个类 / 一个接口，不要一次生成整个模块。
3. **不要自作主张加功能**。需求文档里"明确不做"的清单是有效的。
4. **改完自己验证**：跑 `mvn -B test`；涉及接口的说明怎么测。
5. **改数据库要同步文档**（见第 6 节最后一条）。
6. **不确定就问**，尤其是涉及技术选型、表结构、权限边界的地方——猜错比问慢得多。
