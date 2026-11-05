#!/usr/bin/env bash
# 本地完整版一键启动。
#
# 前置：
#   cp .env.example .env
#   cp .env.full.example .env.full
#   按 .env 填 MySQL / Redis / MinIO / AI Key 等真实值
#
# 说明：
# - .env 负责密码与基础配置（compose 的 ${} 替换也读它）；
# - .env.full 只放完整版内存上限等覆盖项，由本脚本 export 给 compose；
# - 关键 MQ / 消费者开关由 docker-compose.full.yml 的 environment 注入，
#   不依赖 .env 是否写对。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "缺少 .env：先执行 cp .env.example .env 并填写密码" >&2
  exit 1
fi

if [[ ! -f .env.full ]]; then
  echo "缺少 .env.full：先执行 cp .env.full.example .env.full" >&2
  exit 1
fi

# 把 full 的内存上限等变量导出；.env 仍由 compose 自动读取。
set -a
# shellcheck disable=SC1091
source ./.env.full
set +a

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.full.yml)
PROFILES=(--profile rocketmq --profile notification --profile timeout --profile gateway --profile nacos --profile observability)

echo "启动完整版本地栈（RocketMQ / notification / timeout / gateway / nacos / observability）..."
"${COMPOSE[@]}" "${PROFILES[@]}" up -d --build --remove-orphans

echo
"${COMPOSE[@]}" ps
echo
echo "入口："
echo "  管理端 / 官网   http://127.0.0.1:${NGINX_PORT:-8080}/"
echo "  学生 / 维修工   http://127.0.0.1:${NGINX_PORT:-8080}/h5/"
echo "  RocketMQ 控制台 http://127.0.0.1:8082/"
echo "  Nacos 控制台    http://127.0.0.1:8848/nacos/"
echo "  Grafana         http://127.0.0.1:3000/"
