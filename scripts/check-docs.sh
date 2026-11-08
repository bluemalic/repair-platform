#!/usr/bin/env bash
# 文档同步提醒：只做 warning，不阻断构建。
# 用法：BASE_REF=origin/main bash scripts/check-docs.sh
set -u

BASE_REF="${BASE_REF:-origin/main}"
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  BASE_REF="HEAD~1"
fi

changed="$(git diff --name-only "$BASE_REF"...HEAD 2>/dev/null || git diff --name-only "$BASE_REF" HEAD 2>/dev/null || true)"
if [ -z "$changed" ]; then
  echo "check-docs: 没有可比较的变更，跳过"
  exit 0
fi

warn() {
  echo "WARN: $1"
}

has() {
  echo "$changed" | grep -qE "$1"
}

if has 'repair-app/src/main/resources/db/(migration|tenant)/' && ! has '^docs/02-数据库设计\.md$'; then
  warn "改了迁移脚本，但没有改 docs/02-数据库设计.md"
fi

if has 'repair-app/src/main/java/.*/controller/' && ! has '^docs/03-接口规范\.md$'; then
  warn "改了 Controller，但没有改 docs/03-接口规范.md"
fi

if has '^(docker-compose|\.env\.example|Dockerfile|nginx/)' && ! has '^docs/(05-部署文档|10-双版本部署)\.md$'; then
  warn "改了部署配置，但没有改 docs/05 或 docs/10"
fi

if has '^(pom\.xml|repair-[^/]+/pom\.xml)$' && ! has '^docs/04-架构决策记录\.md$'; then
  warn "改了依赖/模块结构，但没有改 docs/04 的 ADR"
fi

echo "check-docs: 提醒结束（不阻断）"
exit 0
