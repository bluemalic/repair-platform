-- ============================================================
-- V13 · 学校统一身份认证（SSO）配置（阶段 7 · ADR-022）
-- ============================================================
--
-- 一所学校一套配置，所以主键直接用 `tenant_id`（一对一表用自然键，再加一个雪花 id 没有意义）。
--
-- 【为什么在**平台库**，不在租户库】
--   SSO 是**登录入口**的一部分，而登录发生在"还不知道该去哪个租户库"之前（ADR-021）：
--   先按学校编码定位学校、读到它的 SSO 配置，才能把用户送去学校的身份提供方。
--   所以它和 `tenant` 一样只存在于平台库——迁移计划与结构一致性测试都把它列为"平台专属表"。
--
-- 【为什么单独一张表，而不是给 tenant 加十几个列】
--   ① `tenant` 会被平台列表/分页反复查，SSO 的 URL 与密钥跟那些查询无关；
--   ② `client_secret` 是凭据，单独一张表便于"查询时默认不带它"（接口只回"是否已配置"）。
--
-- 【URL 为什么分成"浏览器跳转"与"服务端调用"两组】
--   authorize / cas_login 是**浏览器**要跳过去的地址（必须是外网可达的）；
--   token / userinfo / cas_validate 是**服务端**调用的（内网地址更快更稳）。
--   部署在内网或容器里时这两组地址往往不同，写成一个字段只能二选一。
--
-- 【防开放重定向】登录成功后跳回前端的地址只允许用 `admin_redirect_url` / `h5_redirect_url` 这里配的，
--   请求参数只决定"用哪一个"（ADMIN / H5），**不接受任意 URL**——否则这个接口就是一个开放重定向。
--
-- 【身份映射】IdP 返回的属性里哪一个当登录名（学号 / 工号）由 `username_attribute` 决定；
--   平台**不会**因此自动建账号（账号仍由学校在后台上维护，配额与角色都不该被外部系统绕过）。
-- ============================================================

CREATE TABLE `tenant_sso_config` (
    `tenant_id`          bigint       NOT NULL COMMENT '租户ID（一对一）',
    `enabled`            tinyint      NOT NULL DEFAULT 0 COMMENT '是否启用统一认证登录',
    `protocol`           varchar(16)  NOT NULL COMMENT 'OAUTH2 / CAS',
    -- ---------- OAuth2 ----------
    `authorize_url`      varchar(255) DEFAULT NULL COMMENT 'OAuth2 授权地址（浏览器跳转）',
    `token_url`          varchar(255) DEFAULT NULL COMMENT 'OAuth2 换取 token 的地址（服务端调用）',
    `userinfo_url`       varchar(255) DEFAULT NULL COMMENT 'OAuth2 取用户信息的地址（服务端调用）',
    `client_id`          varchar(128) DEFAULT NULL COMMENT 'OAuth2 客户端 ID',
    `client_secret`      varchar(255) DEFAULT NULL COMMENT 'OAuth2 客户端密钥（接口只回"是否已配置"）',
    `scope`              varchar(64)  DEFAULT NULL COMMENT 'OAuth2 scope，留空用 openid profile',
    -- ---------- CAS ----------
    `cas_login_url`      varchar(255) DEFAULT NULL COMMENT 'CAS 登录地址（浏览器跳转）',
    `cas_validate_url`   varchar(255) DEFAULT NULL COMMENT 'CAS ticket 校验地址（服务端调用，带 format=JSON）',
    -- ---------- 身份映射与落点 ----------
    `username_attribute` varchar(64)  NOT NULL DEFAULT 'username' COMMENT 'IdP 返回的哪个属性是登录名（学号/工号）',
    `admin_redirect_url` varchar(255) DEFAULT NULL COMMENT '登录成功后跳回管理端的地址（防开放重定向：只允许这里配的）',
    `h5_redirect_url`    varchar(255) DEFAULT NULL COMMENT '登录成功后跳回 H5 的地址',
    `create_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`tenant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='学校统一身份认证配置（ADR-022）';
