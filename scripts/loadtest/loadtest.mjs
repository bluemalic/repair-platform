#!/usr/bin/env node
/**
 * 压测生成器（`docs/01` §5 的性能目标，报告见 `docs/08-压测报告.md`）。
 *
 * 为什么自己写而不用 k6 / JMeter：
 *   ① 零依赖——只用 Node 自带的 fetch，Windows 上 npm 都不用装东西；
 *   ② 场景要按权重混着打（提交 / 列表 / 看板），而且要复用租户内的登录态，
 *      这些配置在通用工具里要写更多胶水；
 *   ③ 它是**仓库里的一个文件**：参数、口径、判成功的标准都看得见，谁都能复跑。
 *
 * 用法：
 *   node scripts/loadtest/loadtest.mjs --base http://127.0.0.1:8082 \
 *        --scenario submit --qps 100 --duration 60 --concurrency 40
 *
 * 场景：
 *   submit  学生提交报修（写路径，对应"峰值 100 QPS ≈ 6000 单/分钟"）
 *   list    管理端工单列表（读路径，P99 目标 300ms）
 *   dash    统计看板总览（读路径，目标 800ms）
 *   mixed   按 10% 提交 / 60% 列表 / 30% 看板混合（更接近真实峰值的构成）
 *
 * 判成功的标准（与接口契约一致）：HTTP 200 且 body.code === 0。
 * 业务失败（比如 10004 限流）也算失败——**压测要把限流打出来**，否则会漏掉"高并发下自己被限流"这种事。
 */
import { parseArgs } from 'node:util'

const OPTIONS = {
  base: { type: 'string', default: 'http://127.0.0.1:8080' },
  scenario: { type: 'string', default: 'submit' },
  qps: { type: 'string', default: '100' },
  duration: { type: 'string', default: '60' },
  concurrency: { type: 'string', default: '40' },
  tenant: { type: 'string', default: 'gdou' },
  student: { type: 'string', default: '20260001' },
  admin: { type: 'string', default: 'admin' },
  password: { type: 'string', default: 'Repair@2026' },
  code: { type: 'string', default: '482913' },
  category: { type: 'string', default: '1' },
  label: { type: 'string', default: '' },
  json: { type: 'string', default: '' },
  yes: { type: 'boolean', default: false },
}

const { values: args } = parseArgs({ options: OPTIONS, allowPositionals: false })
const qps = Number(args.qps)
const durationSeconds = Number(args.duration)
const concurrency = Number(args.concurrency)
const base = args.base.replace(/\/$/, '')

/** 权重：混合场景更接近真实峰值（读多写少），但写路径单独也要打满 */
const SCENARIOS = {
  submit: { submit: 1 },
  list: { list: 1 },
  dash: { dash: 1 },
  mixed: { submit: 0.1, list: 0.6, dash: 0.3 },
}

const weights = SCENARIOS[args.scenario]
if (!weights) {
  console.error(`--scenario 只支持 ${Object.keys(SCENARIOS).join(' / ')}`)
  process.exit(1)
}

async function api(path, { method = 'GET', token, body } = {}) {
  const resp = await fetch(base + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await resp.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    json = { code: -1, message: text.slice(0, 80) }
  }
  return { status: resp.status, body: json }
}

async function login(tenantCode, username, password) {
  const { status, body } = await api('/api/auth/login', {
    method: 'POST',
    body: { tenantCode, username, password },
  })
  if (status !== 200 || body.code !== 0) {
    throw new Error(`登录失败 ${username}: HTTP ${status} code=${body.code} ${body.message ?? ''}`)
  }
  return body.data.tokenValue
}

/* ==================== 三个请求动作 ==================== */

const actions = {
  async submit(ctx) {
    // 每个虚拟用户提交的工单描述都不一样：一来更像真实流量，二来便于事后抽查
    const seq = ++ctx.submitSeq
    return api('/api/student/tickets', {
      method: 'POST',
      token: ctx.studentToken,
      body: {
        repairCode: args.code,
        categoryId: Number(args.category),
        description: `压测工单 #${seq}（${ctx.label}）`,
        urgency: 1,
      },
    })
  },
  async list(ctx) {
    return api('/api/admin/tickets?pageNum=1&pageSize=10', { token: ctx.adminToken })
  },
  async dash(ctx) {
    return api('/api/admin/statistics/overview', { token: ctx.adminToken })
  },
}

/** 按权重抽一个动作（每次请求现抽，避免"先打完一半提交再打列表"这种不真实的顺序） */
function pickAction() {
  const roll = Math.random()
  let acc = 0
  for (const [name, weight] of Object.entries(weights)) {
    acc += weight
    if (roll <= acc) {
      return name
    }
  }
  return Object.keys(weights)[0]
}

/* ==================== 压测主循环 ==================== */

const latencies = []
const errors = new Map()
let sent = 0
let succeeded = 0
let inFlight = 0
let skipped = 0
let done = false

function record(ok, latency, error) {
  latencies.push(latency)
  if (ok) {
    succeeded++
  } else {
    errors.set(error, (errors.get(error) ?? 0) + 1)
  }
}

/**
 * 发一个请求并记账。
 *
 * **每个 tick 只发一个**（不是"起一个会自己循环的虚拟用户"）：那样写等于把目标速率乘上并发数，
 * 我第一次就是这么写的——20 QPS 实际打了 64 QPS，而报告里还写着"达成率 321%"。
 * 速率控制与并发控制分开：tick 决定"什么时候发"，`concurrency` 决定"最多几个在途"。
 */
async function fire(ctx) {
  // 背压：在途请求到上限就**跳过这个 tick**并计数。跳过而不是排队等——
  // 排队会让测出来的延迟里混进客户端自己的排队时间，而"每秒被挤掉多少次"本身就是服务端饱和的信号
  if (inFlight >= concurrency) {
    skipped++
    return
  }
  const name = pickAction()
  inFlight++
  sent++
  const startedAt = performance.now()
  try {
    const { status, body } = await actions[name](ctx)
    const latency = performance.now() - startedAt
    if (status === 200 && body.code === 0) {
      record(true, latency)
    } else {
      record(false, latency, `${name} HTTP ${status} code=${body.code ?? '?'}`)
    }
  } catch (e) {
    record(false, performance.now() - startedAt, `${name} ${e.name}: ${String(e.message).slice(0, 60)}`)
  } finally {
    inFlight--
  }
}

function percentile(sorted, p) {
  if (!sorted.length) {
    return 0
  }
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[Math.max(0, index)]
}

function printSummary(elapsedSeconds) {
  const sorted = [...latencies].sort((a, b) => a - b)
  const total = latencies.length
  const successRate = total === 0 ? 0 : (succeeded / total) * 100
  console.log('')
  console.log(`场景 ${args.scenario}（${args.label || '未命名'}）  目标 ${qps} QPS × ${durationSeconds}s`)
  console.log(`  实际完成：${total} 次 / ${elapsedSeconds.toFixed(1)}s = ${(total / elapsedSeconds).toFixed(1)} QPS（达成率 ${((total / elapsedSeconds / qps) * 100).toFixed(1)}%）`)
  console.log(`  成功率：${successRate.toFixed(3)}%（${succeeded}/${total}）`)
  console.log(`  延迟(ms)：P50 ${percentile(sorted, 50).toFixed(1)} · P95 ${percentile(sorted, 95).toFixed(1)} · P99 ${percentile(sorted, 99).toFixed(1)} · MAX ${percentile(sorted, 100).toFixed(1)}`)
  if (skipped > 0) {
    console.log(`  ⚠️ 有 ${skipped} 个 tick 因并发上限（${concurrency}）被挤掉——服务端已经跟不上这个速率了`)
  }
  if (errors.size) {
    console.log('  失败分布：')
    for (const [error, count] of [...errors.entries()].sort((a, b) => b[1] - a[1])) {
      console.log(`    ${count} × ${error}`)
    }
  }
  return { total, successRate, sorted }
}

/* ==================== 起跑 ==================== */

console.log(`目标：${base}  场景：${args.scenario}  速率：${qps} QPS  时长：${durationSeconds}s  并发上限：${concurrency}`)
if (!args.yes && !/^https?:\/\/(127\.0\.0\.1|localhost)/.test(base)) {
  console.log('')
  console.log('⚠️  目标不是本机——这会往那个环境里真写数据（提交场景会建工单）。')
  console.log(`⚠️  将在 5 秒后开始，Ctrl+C 可以取消。`)
  await new Promise((resolve) => setTimeout(resolve, 5000))
}

const label = args.label || `${args.scenario}-${qps}qps`
const ctx = { label, submitSeq: 0 }
ctx.studentToken = await login(args.tenant, args.student, args.password)
ctx.adminToken = args.admin ? await login(args.tenant, args.admin, args.password) : null
console.log('登录完成，开始打流…')

const startedAt = performance.now()
// 速率控制：把一轮时间切成 duration*qps 个槽位，每个槽位到点**发一个请求**。
// 按绝对时间算槽位（而不是 sleep(1000/qps)），否则每次调度的误差会累积成明显的速率偏低。
const tickMs = 1000 / qps
const totalTicks = Math.round(durationSeconds * qps)
let tick = 0
const ticker = setInterval(() => {
  const now = performance.now()
  while (tick < totalTicks && (tick * tickMs) + startedAt <= now) {
    tick++
    fire(ctx).catch(() => {})
  }
  if (tick >= totalTicks) {
    clearInterval(ticker)
    done = true
  }
}, Math.max(1, Math.floor(tickMs / 2)))

// 时间到就收尾：等在途请求跑完（最多再等 10 秒）
await new Promise((resolve) => {
  const check = setInterval(() => {
    if (done && inFlight === 0) {
      clearInterval(check)
      resolve()
    }
  }, 50)
  setTimeout(() => {
    clearInterval(check)
    resolve()
  }, durationSeconds * 1000 + 10_000)
})
done = true

const elapsedSeconds = (performance.now() - startedAt) / 1000
const summary = printSummary(elapsedSeconds)

if (args.json) {
  const { writeFileSync } = await import('node:fs')
  writeFileSync(args.json, JSON.stringify({
    label,
    scenario: args.scenario,
    base,
    qps,
    durationSeconds,
    concurrency,
    total: summary.total,
    elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
    achievedQps: Number((summary.total / elapsedSeconds).toFixed(1)),
    successRate: Number(summary.successRate.toFixed(3)),
    p50: Number(percentile(summary.sorted, 50).toFixed(1)),
    p95: Number(percentile(summary.sorted, 95).toFixed(1)),
    p99: Number(percentile(summary.sorted, 99).toFixed(1)),
    max: Number(percentile(summary.sorted, 100).toFixed(1)),
    skipped,
    errors: Object.fromEntries(errors),
  }, null, 2))
  console.log(`  明细已写入 ${args.json}`)
}
