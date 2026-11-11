// Agent 评测（docs/07 §5）：报修受理 7 条 + 智能分诊 3 条 + 写库不变量。
//
// 用法：
//   node scripts/ai-eval/run-agent-eval.mjs --password <演示口令>
//   node scripts/ai-eval/run-agent-eval.mjs --skip-snapshot   # 没有本地 MySQL 时
//
// 与问数评测的区别：这里的判定是**自动**的——受理的 `ready` / `missing` / `trace` 都是服务端算出来的
// 结构化结果，不依赖"数字对不对"，所以能直接判。分诊按工单描述关键词找单，找不到就 SKIP 而不是 FAIL
// （演示数据变了不该算模型的错）。
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { api, arg, isOk, login, outDir, pad, writeReport } from './lib.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const cases = JSON.parse(readFileSync(join(here, 'agent-cases.json'), 'utf8'));

const baseUrl = arg('base-url', process.env.AI_EVAL_BASE_URL || 'http://127.0.0.1:18090');
const tenantCode = arg('tenant', process.env.AI_EVAL_TENANT || 'gdou');
const adminUser = arg('admin-user', process.env.AI_EVAL_USER || 'admin');
const studentUser = arg('student-user', process.env.AI_EVAL_STUDENT || '20260001');
const password = arg('password', process.env.REPAIR_DEMO_PASSWORD || '');
const dir = outDir(arg('out', ''));
const mysqlContainer = arg('mysql-container', process.env.AI_EVAL_MYSQL_CONTAINER || 'repair-mysql');
const mysqlPassword = process.env.MYSQL_PASSWORD || arg('mysql-password', '');
const skipSnapshot = process.argv.includes('--skip-snapshot');

if (!password) {
  console.error('缺少口令：--password <演示口令> 或 export REPAIR_DEMO_PASSWORD=...');
  process.exit(2);
}

/**
 * 写库不变量：跑前跑后各取一次快照做 diff（docs/07 §5.1 第 3 条）。
 *
 * <p>用 `docker exec` 直查而不是走 API：没有任何接口会暴露"全表状态"，
 * 而这条不变量恰好要求覆盖 **所有** 工单（不只是我能看到的那几页）。
 */
function snapshot() {
  if (skipSnapshot || !mysqlPassword) {
    return null;
  }
  // 注意引号：整条 SQL 会被塞进 `mysql -e "..."` 里，内部只能用单引号，
  // 用双引号会被外层提前截断（第一次跑就是这么失败的）。
  const sql = [
    "SELECT COUNT(*), IFNULL(SUM(status),0), IFNULL(MAX(update_time),'') FROM ticket",
    'SELECT COUNT(*) FROM ticket_log',
    'SELECT COUNT(*) FROM notification',
  ].join('; ');
  try {
    const output = execSync(
      `docker exec ${mysqlContainer} mysql -uroot -p${mysqlPassword} -N -e "${sql}" repair_gdou`,
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
    return output.trim().split('\n').map((line) => line.trim()).join(' | ');
  } catch (error) {
    // 容器名/口令不对不算评测失败：这条不变量是加分项，缺了要说清楚而不是静默跳过
    console.warn(`  ! 快照失败（${error.message.split('\n')[0]}），跳过写库不变量检查`);
    return null;
  }
}

/** 受理用例的判定：返回不符合预期的原因列表（空数组 = 通过）。 */
function judgeIntake(testCase, data) {
  const problems = [];
  if (data.ready !== testCase.expectReady) {
    problems.push(`ready=${data.ready}，期望 ${testCase.expectReady}`);
  }
  const missing = data.missing || [];
  if (testCase.expectMissingEmpty && missing.length > 0) {
    problems.push(`missing=${JSON.stringify(missing)}，期望为空`);
  }
  if (testCase.expectMissingNotEmpty && missing.length === 0) {
    problems.push('missing 为空，期望非空');
  }
  for (const key of testCase.expectMissingIncludes || []) {
    if (!missing.includes(key)) {
      problems.push(`missing 缺 ${key}`);
    }
  }
  for (const field of testCase.expectNoDraftField || []) {
    if (data.draft?.[field] != null && data.draft[field] !== '') {
      problems.push(`draft.${field} 不该有值（模型编造了位置）`);
    }
  }
  if (testCase.expectRepairCode && data.repairCode !== testCase.expectRepairCode) {
    problems.push(`repairCode=${data.repairCode}，期望原样带回`);
  }
  const tools = (data.trace || []).map((step) => step.tool);
  for (const tool of testCase.expectTools || []) {
    if (!tools.includes(tool)) {
      problems.push(`trace 缺 ${tool}（id 得是查出来的，不能猜）`);
    }
  }
  if (testCase.expectNoFakeId && data.draft?.categoryId === 99) {
    problems.push('采用了不存在的类别 99');
  }
  return problems;
}

/**
 * 从字典里现取一个**启用中**的报修码。
 *
 * <p>用例不写死码：演示重置每次都会重新生成，写死的码在下一轮库里就成了"无效码"，
 * 于是 A3 会被记成失败——那是数据集变了，不是模型退化（第一次跑就踩到了）。
 */
async function resolveRepairCode(headers) {
  const page = await api(baseUrl, '/api/admin/repair-codes?pageNum=1&pageSize=1&status=1', { headers });
  const first = page.data?.list?.[0];
  return first?.code || null;
}

async function runIntake(headers) {
  console.log(`\n== 受理（${cases.intake.length} 条，自动判定）==`);
  const datasetCode = await resolveRepairCode(headers);
  const records = [];
  for (const testCase of cases.intake) {
    // 用例声明"用数据集里的码"时，这里补上；取不到就 SKIP（不把数据缺失算成模型失败）
    const repairCode = testCase.useDatasetRepairCode ? datasetCode : testCase.repairCode;
    if (testCase.useDatasetRepairCode && !repairCode) {
      console.log(`${pad(testCase.id, 3)} ${pad('SKIP', 6)} 库里没有启用中的报修码`);
      records.push({ ...testCase, status: 'skip' });
      continue;
    }
    // 只有"用数据集里的码"才断言原样带回；A4 故意传无效码，服务端忽略它是正确行为，不能要求回显
    const effectiveCase = testCase.useDatasetRepairCode
      ? { ...testCase, expectRepairCode: repairCode }
      : testCase;

    let sessionId;
    let result;
    // 多轮用例：第一句之后按 followUps 继续，复用 sessionId（服务端靠它保留已填字段）
    for (const message of [testCase.message, ...(testCase.followUps || [])]) {
      const body = { message };
      if (sessionId) body.sessionId = sessionId;
      if (repairCode) body.repairCode = repairCode;
      result = await api(baseUrl, '/api/student/agent/intake', { method: 'POST', body, headers });
      if (!isOk(result)) break;
      sessionId = result.data.sessionId;
    }
    const problems = isOk(result)
      ? judgeIntake(effectiveCase, result.data)
      : [`code=${result.code} ${result.message}`];
    records.push({ ...effectiveCase, result, problems });
    console.log(`${pad(testCase.id, 3)} ${pad(problems.length ? 'FAIL' : 'PASS', 6)} ${testCase.message}${repairCode ? `（码 ${repairCode}）` : ''}`);
    for (const problem of problems) {
      console.log(`      ↳ ${problem}`);
    }
    if (isOk(result)) {
      // 顶层便利字段有时是空的（服务端只保证 draft 里有），报告里以 draft 为准
      const draft = result.data.draft || {};
      console.log(
        `      ready=${result.data.ready} missing=${JSON.stringify(result.data.missing || [])} `
        + `类别=${draft.categoryName || result.data.categoryName || '-'} 楼栋=${draft.buildingName || result.data.buildingName || '-'} `
        + `房间=${draft.room || result.data.room || '-'} 紧急度=${draft.urgency ?? result.data.urgency ?? '-'} `
        + `工具=[${(result.data.trace || []).map((s) => s.tool).join(',')}]`,
      );
    }
  }
  return records;
}

/** 按描述关键词找工单：列表接口不返回描述，所以逐个取详情（12 张，不花模型钱）。 */
async function findTicketByKeywords(headers, keywords) {
  const page = await api(baseUrl, '/api/admin/tickets?pageNum=1&pageSize=50', { headers });
  if (!isOk(page)) {
    return null;
  }
  for (const ticket of page.data.list || []) {
    const detail = await api(baseUrl, `/api/admin/tickets/${ticket.id}`, { headers });
    const description = detail.data?.description || '';
    if (keywords.some((keyword) => description.includes(keyword))) {
      return { id: ticket.id, description, detail: detail.data };
    }
  }
  return null;
}

async function runTriage(headers) {
  console.log(`\n== 分诊（${cases.triage.length} 条，自动判定）==`);
  const records = [];
  for (const testCase of cases.triage) {
    const ticket = await findTicketByKeywords(headers, testCase.matchKeywords);
    if (!ticket) {
      console.log(`${pad(testCase.id, 3)} ${pad('SKIP', 6)} 演示数据里没有含 ${testCase.matchKeywords.join('/')} 的工单`);
      records.push({ ...testCase, status: 'skip' });
      continue;
    }
    const result = await api(baseUrl, `/api/admin/tickets/${ticket.id}/triage`, { method: 'POST', headers });
    const problems = [];
    if (!isOk(result)) {
      problems.push(`code=${result.code} ${result.message}`);
    } else {
      const data = result.data;
      if (!data.suggestedCategoryName) problems.push('没给出建议类别');
      if ((data.suggestedUrgency ?? 0) < testCase.expectUrgencyAtLeast) {
        problems.push(`紧急度=${data.suggestedUrgency}，期望 ≥${testCase.expectUrgencyAtLeast}`);
      }
      if (!data.reason) problems.push('没给理由');
    }
    records.push({ ...testCase, ticketId: ticket.id, result, problems });
    console.log(`${pad(testCase.id, 3)} ${pad(problems.length ? 'FAIL' : 'PASS', 6)} 工单#${ticket.id} ${ticket.description.slice(0, 40)}`);
    for (const problem of problems) {
      console.log(`      ↳ ${problem}`);
    }
    if (isOk(result)) {
      console.log(`      ${result.data.suggestedCategoryName}(${result.data.suggestedCategoryId}) 紧急度=${result.data.suggestedUrgency} 理由=${result.data.reason}`);
    }
  }
  return records;
}

async function main() {
  console.log(`Agent 评测 → ${baseUrl}（租户 ${tenantCode}）`);
  const adminHeaders = await login({ baseUrl, tenantCode, username: adminUser, password });
  const studentHeaders = await login({ baseUrl, tenantCode, username: studentUser, password });

  const before = snapshot();
  if (before) {
    console.log(`\n写库快照（前）：${before}`);
  }

  const intakeRecords = await runIntake(studentHeaders);
  const triageRecords = await runTriage(adminHeaders);

  const after = snapshot();
  if (before && after) {
    console.log(`写库快照（后）：${after}`);
    console.log(before === after ? '✅ 前后一致（没有写库）' : '❌ 前后不一致：Agent 写了库');
  }

  const judged = [...intakeRecords, ...triageRecords].filter((r) => r.status !== 'skip');
  const failed = judged.filter((r) => (r.problems || []).length > 0);
  const skipped = [...intakeRecords, ...triageRecords].filter((r) => r.status === 'skip');
  const writeUnchanged = before && after ? before === after : null;

  const markdown = [
    `# Agent 评测（${new Date().toISOString().slice(0, 19).replace('T', ' ')}）`,
    '',
    `- 环境：\`${baseUrl}\`，租户 \`${tenantCode}\`，受理用 \`${studentUser}\`，分诊用 \`${adminUser}\``,
    `- 硬判定：${judged.length - failed.length}/${judged.length} 符合预期；SKIP ${skipped.length} 条`,
    `- 写库不变量：${writeUnchanged === null ? '未检查（无快照）' : writeUnchanged ? '前后一致 ✅' : '**有写库 ❌**'}`,
    '',
    '| 用例 | 场景 | 结果 | 说明 |',
    '|---|---|---|---|',
    ...intakeRecords.map((r) => {
      const scenario = `${r.message}${r.followUps ? ` → ${r.followUps.join(' → ')}` : ''}`;
      if (r.status === 'skip') {
        return `| ${r.id} | ${scenario} | SKIP | 库里没有可用的报修码 |`;
      }
      const status = (r.problems || []).length === 0 ? '✅' : '❌';
      const detail = (r.problems || []).join('；')
        || `ready=${r.result?.data?.ready} missing=${JSON.stringify(r.result?.data?.missing || [])}`;
      return `| ${r.id} | ${scenario} | ${status} | ${detail} |`;
    }),
    ...triageRecords.map((r) => {
      if (r.status === 'skip') return `| ${r.id} | ${r.matchKeywords.join('/')} | SKIP | 演示数据里没有匹配工单 |`;
      const status = (r.problems || []).length === 0 ? '✅' : '❌';
      const detail = (r.problems || []).join('；')
        || `${r.result.data.suggestedCategoryName} / 紧急度 ${r.result.data.suggestedUrgency} / ${r.result.data.reason}`;
      return `| ${r.id} | 工单#${r.ticketId} | ${status} | ${detail} |`;
    }),
    '',
  ].join('\n');

  const { mdPath, jsonPath } = writeReport(dir, 'agent', markdown, {
    baseUrl,
    tenantCode,
    before,
    after,
    intake: intakeRecords,
    triage: triageRecords,
  });
  console.log(`\n报告：${mdPath}\n原始：${jsonPath}`);
  process.exit(failed.length > 0 || writeUnchanged === false ? 1 : 0);
}

main().catch((error) => {
  console.error(`评测中止：${error.message}`);
  process.exit(2);
});
