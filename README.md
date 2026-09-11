# 高校后勤报修 SaaS 平台（含 AI 数据助手）

> 一句话：学生扫码报修、系统派单、师傅接单、后勤调度，数据自动汇总，并支持用自然语言直接问数

[![CI](https://github.com/bluemalic/repair-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/bluemalic/repair-platform/actions/workflows/ci.yml)

**在线演示**：http://你的服务器IP:8080 　|　**接口文档**：http://你的服务器IP:8080/doc.html

---

## 项目背景

高校后勤维修普遍存在**响应慢、流程不透明、数据难统计**的问题：学生报修靠电话或微信群，后勤靠纸笔登记，师傅靠口头派单，工单进度无人知晓，月底统计全靠手工汇总。

本项目把这条链路完整线上化，并把数据打通到"能用自然语言直接问"的程度。

---

## 角色与权限

| 角色 | 能做什么 | 数据可见范围 |
|---|---|---|
| 学生 | 提交报修、查看进度、验收评价 | 只有自己的工单 |
| 维修工 | 接单、处理、上传结果 | 只有自己负责的楼栋与工单 |
| 后勤管理 | 派单、转派、监控、看统计、使用 AI 问数 | 全部工单 |

---

## 架构图

```
┌────────────────────────────┬──────────────┐
│  学生端 + 维修工端          │  后勤管理端   │
│  uni-app（先编译 H5）       │  Vue 3 后台   │
└─────────────┬──────────────┴──────┬───────┘
       └──────────────┼──────────────┘
                      │  HTTPS / REST + SSE
              ┌───────▼────────┐
              │     Nginx      │
              └───────┬────────┘
              ┌───────▼────────────────────┐
              │   Spring Boot 3 应用        │
              │  ┌──────────────────────┐  │
              │  │ 鉴权 / 数据权限拦截器  │  │
              │  ├──────────────────────┤  │
              │  │ 工单域 │ 统计域 │ AI 域│  │
              │  └──────────────────────┘  │
              └───┬────────┬────────┬──────┘
                  │        │        │
             ┌────▼──┐ ┌───▼───┐ ┌──▼─────┐
             │MySQL 8│ │Redis 7│ │ MinIO  │
             └───────┘ └───────┘ └────────┘
```

---

## 技术栈

| 层次 | 选型 |
|---|---|
| 框架 | Spring Boot 3 + MyBatis-Plus |
| 鉴权 | Sa-Token |
| 数据库 | MySQL 8 |
| 缓存 / 队列 | Redis 7（缓存 + Token + 延迟队列 + 向量检索） |
| 对象存储 | MinIO |
| AI | LangChain4j + DeepSeek API |
| 接口文档 | Knife4j (OpenAPI 3) |
| 前端 | 管理端 Vue 3 + Element Plus；学生 / 维修工端 **uni-app**（先编译 H5，备案后可编译小程序） |
| 部署 | Docker + Docker Compose + Nginx |
| CI/CD | GitHub Actions |

---

## 快速开始（两条命令）

```bash
# 1. 准备环境变量
cp .env.example .env    # 然后填入数据库密码、DeepSeek API Key

# 2. 启动（docs/schema.sql 会在 MySQL 首次启动时自动执行）
docker compose up -d
```

访问：接口文档 http://localhost:8080/doc.html 　|　管理端 http://localhost:8080

> 数据库已存在时不会重复执行初始化脚本；要重建库见 [部署文档](docs/05-部署文档.md)。

---

## 目录结构

```
├── docs/                    设计文档（需求 / 数据库 / 接口 / ADR）
├── src/main/java/com/bluemalic/repair/
│   ├── common/              统一返回体、异常、常量、工具
│   ├── config/              配置类（MyBatis-Plus、Sa-Token、Redis、Knife4j、线程池）
│   ├── controller/          按端分包：student / worker / admin
│   ├── service/             业务逻辑
│   ├── mapper/              数据访问
│   ├── entity/ dto/ vo/     实体 / 入参 / 出参
│   ├── converter/           Entity / DTO / VO 转换
│   ├── interceptor/         数据权限、鉴权
│   ├── ai/                  Schema 检索、SQL 生成、SQL 安全网关
│   └── job/                 定时任务
├── web-admin/               后勤管理端（Vue 3）
├── miniapp/                 学生 / 维修工端（uni-app，先编译 H5）
├── Dockerfile
├── docker-compose.yml
└── .github/workflows/ci.yml
```

---

## 核心设计

### 1. 三方数据隔离

通过 MyBatis 拦截器自动为 SQL 注入数据范围条件（学生 → 本人；维修工 → 负责楼栋），把越权风险从"每个 SQL 手写 where"收敛到框架层，新增接口零成本接入。

### 2. 工单状态机

8 种状态 + 合法跃迁规则，禁止跳状态。派单与接单通过「唯一索引 + 状态前置校验」实现幂等。

### 3. 超时升级

用 Redis ZSet 延迟队列替代定时轮询，24 小时未接单提醒调度方、48 小时未处理自动升级。

### 4. AI 数据助手

自然语言 → Schema 检索 → SQL 生成 → **安全网关校验** → 执行 → 出图与结论。

- **SQL 安全网关**：只读账号、白名单校验、禁 DDL/DML、超时熔断、返回行数上限
- **数据权限继承**：模型生成的 SQL 同样经过数据权限拦截器
- **评测集**：自建「自然语言问题 → 期望 SQL」数据集，量化准确率

---

## 文档

| 文档 | 说明 |
|---|---|
| [需求文档](docs/01-需求文档.md) | 角色、用户故事、功能清单 |
| [数据库设计](docs/02-数据库设计.md) | ER 图、表结构、索引设计 |
| [接口规范](docs/03-接口规范.md) | 返回体、错误码、分页、鉴权 |
| [架构决策记录](docs/04-架构决策记录.md) | 关键技术取舍与理由 |
| [部署文档](docs/05-部署文档.md) | 服务器初始化与发布流程 |

---

## 开发约定

- 分支：`main`（始终可部署）+ 短生命周期的 `feature/*`（修改用 `fix/*`）
- 每个功能走分支 + PR 合入 `main`，不直接推 `main`
- 不设长期 `develop`：单人项目里它只会带来"哪个分支才是可部署版本"的歧义和多余的合并开销
- 提交：[Conventional Commits](https://www.conventionalcommits.org/)，如 `feat(order): 新增工单状态机`
- 所有改动走 PR，`main` 开启分支保护
- 代码格式计划在阶段 5 接入 Spotless（CI 已预留检查步骤，当前尚未启用）

---

## License

MIT
