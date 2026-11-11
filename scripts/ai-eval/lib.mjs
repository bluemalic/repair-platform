// AI 评测脚本的公共部分：登录、调接口、写报告。
//
// 为什么是 Node 而不是 .ps1/.sh：仓库里已经有 `scripts/loadtest/loadtest.mjs`，
// 用法一致（`node scripts/ai-eval/run-eval.mjs`），Windows 与 Linux 同一份代码，
// 不依赖 jq / curl 的版本差异。
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

/** 读命令行参数：`--base-url http://...`，缺省值给第二参。 */
export function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  if (index >= 0 && process.argv[index + 1] && !process.argv[index + 1].startsWith('--')) {
    return process.argv[index + 1];
  }
  return fallback;
}

/** 请求超时：问数要两次模型调用，给足 120s；超时按失败记，不重试（重试会让成本翻倍）。 */
const TIMEOUT_MS = 120_000;

export async function api(baseUrl, path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    // 非 JSON（网关 502、nginx 502 页、SSE 误用）也要能看到原文，否则失败原因会变成 "Unexpected token"
    return { code: -1, message: `HTTP ${response.status}: ${text.slice(0, 200)}` };
  }
}

/**
 * 登录拿 token。
 *
 * <p>返回的 `tokenName` 由服务端给（Sa-Token 的 token-name 可配），不要在这里写死 `satoken`——
 * 写死的话改一次配置评测脚本就跟着坏。
 */
export async function login({ baseUrl, tenantCode, username, password }) {
  if (!password) {
    throw new Error('缺少口令：传 --password 或设置环境变量 REPAIR_DEMO_PASSWORD（演示重置时的那个口令）');
  }
  const result = await api(baseUrl, '/api/auth/login', {
    method: 'POST',
    body: { tenantCode, username, password },
  });
  if (result.code !== 0 || !result.data?.tokenValue) {
    throw new Error(`登录失败：code=${result.code} message=${result.message}`);
  }
  // 服务端把 token 放在 Authorization 头里，前缀是 Bearer（docs/03 §5.1 的前端约定）。
  // `tokenName` 是服务端给的，但**前缀不会随登录返回**，所以这里按协议补；
  // 换成自定义头（比如 satoken）时不需要前缀。
  const headerName = result.data.tokenName || 'Authorization';
  const value = /^authorization$/i.test(headerName)
    ? `Bearer ${result.data.tokenValue}`
    : result.data.tokenValue;
  return { [headerName]: value };
}

/** 结果是否成功。 */
export const isOk = (result) => result.code === 0;

/** 一次调用的耗时统计（p50 / p95），用于对比不同引擎。 */
export function percentiles(values) {
  if (values.length === 0) {
    return { p50: 0, p95: 0, max: 0 };
  }
  const sorted = [...values].sort((a, b) => a - b);
  const pick = (ratio) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * ratio))];
  return { p50: pick(0.5), p95: pick(0.95), max: sorted[sorted.length - 1] };
}

/** 结果目录（默认 `scripts/ai-eval/results/`，已在 .gitignore 里）。 */
export function outDir(custom) {
  const dir = custom || join(process.cwd(), 'scripts', 'ai-eval', 'results');
  mkdirSync(dirname(join(dir, 'x')), { recursive: true });
  return dir;
}

/** 写一份报告：同名 .md（给人看、可直接贴进 docs/07）+ .json（给下次对比）。 */
export function writeReport(dir, name, markdown, json) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const mdPath = join(dir, `${name}-${stamp}.md`);
  const jsonPath = join(dir, `${name}-${stamp}.json`);
  writeFileSync(mdPath, markdown, 'utf8');
  writeFileSync(jsonPath, JSON.stringify(json, null, 2), 'utf8');
  return { mdPath, jsonPath };
}

/** 数字对齐输出（控制台看一行一条，不要整段 JSON 刷屏）。 */
export function pad(value, width) {
  const text = String(value ?? '');
  return text.length >= width ? text : text + ' '.repeat(width - text.length);
}
