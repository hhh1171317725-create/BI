import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appDir = path.dirname(fileURLToPath(import.meta.url));
const host = process.env.DHH_HOST || "127.0.0.1";
const port = Number(process.env.DHH_PORT || 8765);
const runtimeDir = process.env.DHH_RUNTIME_DIR || path.join(appDir, ".runtime");
const cachePath = path.join(runtimeDir, "report-cache.json");
const jdCachePath = path.join(runtimeDir, "jd-report-cache.json");
const exportUrl = "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport";
const jdExportUrl = "https://report.rockorca.com/api/marketingJdCpaDaily/getMarketingJdCpaDailyExport?dimType=detail";
const numericFields = ["消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数"];
const jdNumericFields = [
  "转化数", "计费转化数", "去重订单总数", "首购订单总数", "回流订单总数",
  "首购有效订单数", "回流有效订单数", "首购无效订单数", "回流无效订单数",
  "首购已完成订单", "回流已完成订单", "消耗", "条件内预估赔付金额",
  "首购预估佣金", "回流预估佣金", "首购实际佣金", "回流实际佣金",
];
let rows = [];
let cachedAt = "";
let jdRows = [];
let jdCachedAt = "";

function number(value) {
  const parsed = Number(String(value ?? "0").replaceAll(",", ""));
  return Number.isFinite(parsed) ? parsed : 0;
}

function projectFromTask(task) {
  if (task.includes("闲鱼")) return "淘宝闲鱼促活";
  if (task.includes("MCVR")) return "淘宝闪购MCVR";
  if (task.includes("CVR")) return "淘宝促购CVR";
  if (task.includes("UV")) return "淘宝促活UV";
  return "其他项目";
}

function parseCsv(text) {
  const records = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted && char === '"' && text[index + 1] === '"') {
      field += '"';
      index += 1;
    } else if (char === '"') {
      quoted = !quoted;
    } else if (!quoted && char === ",") {
      row.push(field);
      field = "";
    } else if (!quoted && (char === "\n" || char === "\r")) {
      if (char === "\r" && text[index + 1] === "\n") index += 1;
      row.push(field);
      if (row.length > 1 || row[0]) records.push(row);
      row = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field || row.length) {
    row.push(field);
    records.push(row);
  }
  const [headers = [], ...data] = records;
  return data.map((values) => Object.fromEntries(headers.map((header, index) => [header.trim(), values[index] ?? ""])));
}

function parseDhhAccounts(value) {
  let parsed;
  try {
    parsed = JSON.parse(String(value || "[]"));
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return parsed.flatMap((account) => {
    const id = String(account?.media_ad_account_id || "").trim();
    const name = String(account?.media_ad_account_name || "").trim();
    if (!id && !name) return [];
    return [{
      账户ID: id,
      账户名称: name || `账户 ${id}`,
      消耗: number(account?.media_total_cost),
      现金消耗: number(account?.media_cash_cost),
      赠款消耗: number(account?.media_reward_cost),
    }];
  });
}

async function fetchRows(token, userId) {
  if (!token?.trim()) throw new Error("请粘贴当前 x-token");
  const response = await fetch(exportUrl, {
    headers: {
      Accept: "application/json, text/plain, */*",
      Referer: "https://report.rockorca.com/",
      "X-Token": token.trim(),
      "X-User-Id": userId?.trim() || "20",
      Cookie: `x-token=${token.trim()}`,
    },
  });
  if (!response.ok) throw new Error(`报表接口请求失败：${response.status}`);
  const raw = await response.text();
  return parseCsv(raw).flatMap((record) => {
    const date = String(record.日期 || "").trim();
    if (!date || date === "-") return [];
    const task = String(record.任务名 || "").trim() || "未填写";
    const row = {
      日期: date.slice(0, 10),
      媒体: String(record.媒体 || "").trim() || "未填写",
      账户列表: parseDhhAccounts(record.账户信息),
      优化师: String(record.优化师 || "").trim() || "未填写",
      任务名: task,
      项目: projectFromTask(task),
    };
    numericFields.forEach((field) => { row[field] = number(record[field]); });
    return [row];
  });
}

async function fetchJdRows(token, userId) {
  if (!token?.trim()) throw new Error("请粘贴当前 x-token");
  const response = await fetch(jdExportUrl, {
    headers: {
      Accept: "application/json, text/plain, */*",
      Referer: "https://report.rockorca.com/",
      "X-Token": token.trim(),
      "X-User-Id": userId?.trim() || "20",
      Cookie: `x-token=${token.trim()}`,
    },
  });
  if (!response.ok) throw new Error(`京东报表接口请求失败：${response.status}`);
  const raw = await response.text();
  return parseJdCsv(raw);
}

function parseJdCsv(raw) {
  return parseCsv(raw).flatMap((record) => {
    const date = String(record.业务日期 || "").trim();
    if (!date || date === "-") return [];
    const row = {
      日期: date.slice(0, 10),
      推广位ID: String(record.推广位ID || "").trim() || "未填写",
      推广位名称: String(record.推广位名称 || "").trim() || "未填写",
      媒体: String(record.媒体 || "").trim() || "未填写",
      媒体账户ID: String(record.媒体账户ID || "").trim() || "未填写",
      媒体账户名称: String(record.媒体账户名称 || "").trim() || "未填写",
      推客用户名: String(record.推客用户名 || "").trim() || "未填写",
      优化师: String(record.优化师 || "").trim() || "未填写",
    };
    jdNumericFields.forEach((field) => { row[field] = number(record[field]); });
    row.条件内预估赔付金额 = number(record["条件内预估赔付金额(当日)"]);
    return [row];
  });
}

async function saveCache() {
  await fs.mkdir(runtimeDir, { recursive: true });
  cachedAt = new Date().toISOString();
  const temporaryPath = `${cachePath}.tmp`;
  await fs.writeFile(temporaryPath, JSON.stringify({ cachedAt, rows }), "utf8");
  await fs.rename(temporaryPath, cachePath);
}

async function restoreCache() {
  if (rows.length) return;
  const cached = JSON.parse(await fs.readFile(cachePath, "utf8"));
  if (!Array.isArray(cached.rows) || !cached.rows.length) throw new Error("暂无已保存的数据，请粘贴 x-token 后加载一次");
  rows = cached.rows;
  cachedAt = cached.cachedAt || "";
}

async function saveJdCache() {
  await fs.mkdir(runtimeDir, { recursive: true });
  jdCachedAt = new Date().toISOString();
  const temporaryPath = `${jdCachePath}.tmp`;
  await fs.writeFile(temporaryPath, JSON.stringify({ cachedAt: jdCachedAt, rows: jdRows }), "utf8");
  await fs.rename(temporaryPath, jdCachePath);
}

async function restoreJdCache() {
  if (jdRows.length) return;
  const cached = JSON.parse(await fs.readFile(jdCachePath, "utf8"));
  if (!Array.isArray(cached.rows) || !cached.rows.length) throw new Error("暂无已保存的京东数据，请粘贴 x-token 后加载一次");
  jdRows = cached.rows;
  jdCachedAt = cached.cachedAt || "";
}

function aggregate(data, fields) {
  const groupFields = Array.isArray(fields) ? fields : [fields];
  const buckets = new Map();
  for (const row of data) {
    const key = groupFields.map((field) => row[field]).join("\u0001");
    if (!buckets.has(key)) {
      buckets.set(key, { dimensions: Object.fromEntries(groupFields.map((field) => [field, row[field]])), values: Object.fromEntries(numericFields.map((field) => [field, 0])) });
    }
    const bucket = buckets.get(key).values;
    numericFields.forEach((field) => { bucket[field] += row[field]; });
  }
  return [...buckets.values()].map(({ dimensions, values }) => {
    const spend = values.消耗;
    const cash = values.现金消耗;
    const commission = values.预估佣金;
    const settled = values.结算数;
    return {
      ...dimensions,
      ...Object.fromEntries(Object.entries(values).map(([key, value]) => [key, Number(value.toFixed(2))])),
      现金利润: Number((commission - cash).toFixed(2)),
      ROI: spend ? Number((commission / spend).toFixed(4)) : 0,
      现金ROI: cash ? Number((commission / cash).toFixed(4)) : 0,
      结算单价: settled ? Number((commission / settled).toFixed(2)) : 0,
      转化成本: values.转化数 ? Number((spend / values.转化数).toFixed(2)) : 0,
      注册成本: values.注册数 ? Number((spend / values.注册数).toFixed(2)) : 0,
    };
  }).sort((left, right) => right.消耗 - left.消耗);
}

function previousBeijingDate(now = new Date()) {
  const beijing = new Date(now.getTime() + (8 * 60 * 60 * 1000));
  beijing.setUTCDate(beijing.getUTCDate() - 1);
  return beijing.toISOString().slice(0, 10);
}

function buildDhhAlerts(data, date = previousBeijingDate()) {
  const dailyRows = data.filter((row) => row.日期 === date);
  const buckets = new Map();
  for (const row of dailyRows) {
    const listedAccounts = Array.isArray(row.账户列表) ? row.账户列表 : [];
    const positiveAccounts = listedAccounts.filter((account) => number(account.消耗) > 0);
    const relatedAccounts = positiveAccounts.length === 0 && listedAccounts.length === 1
      ? listedAccounts
      : positiveAccounts;
    for (const account of relatedAccounts) {
      const id = String(account.账户ID || "").trim();
      const name = String(account.账户名称 || "").trim() || (id ? `账户 ${id}` : "");
      if (!id && !name) continue;
      const key = id || name;
      if (!buckets.has(key)) {
        buckets.set(key, {
          账户ID: id,
          账户名称: name,
          消耗: 0,
          关联注册数: 0,
          关联结算数: 0,
        });
      }
      const bucket = buckets.get(key);
      bucket.消耗 += number(account.消耗);
      bucket.关联注册数 += number(row.注册数);
      bucket.关联结算数 += number(row.结算数);
    }
  }
  const accounts = [...buckets.values()].map((account) => ({
    ...account,
    消耗: Number(account.消耗.toFixed(2)),
    关联注册数: Number(account.关联注册数.toFixed(2)),
    关联结算数: Number(account.关联结算数.toFixed(2)),
  }));
  const items = accounts.flatMap((account) => {
    const registrations = account.关联注册数;
    const settlements = account.关联结算数;
    const spend = account.消耗;
    const reasons = [];
    if (registrations < settlements) {
      reasons.push({
        code: "registrations_below_settlements",
        message: `关联注册数 ${registrations} 少于关联结算数 ${settlements}`,
      });
    }
    if (spend >= 100 && registrations === 0) {
      reasons.push({
        code: "spend_without_registration",
        message: `账户消耗 ${Number(spend.toFixed(2))} 元但关联注册数为 0`,
      });
    }
    if (registrations > settlements * 1.1) {
      const message = settlements
        ? `关联注册数 ${registrations} 比关联结算数 ${settlements} 高 ${Number((((registrations - settlements) / settlements) * 100).toFixed(2))}%`
        : `关联结算数为 0，但关联注册数为 ${registrations}`;
      reasons.push({ code: "registrations_over_settlements_10pct", message });
    }
    if (!reasons.length) return [];
    return [{
      账户ID: account.账户ID,
      账户名称: account.账户名称,
      消耗: spend,
      关联注册数: registrations,
      关联结算数: settlements,
      reasons,
    }];
  }).sort((left, right) => right.reasons.length - left.reasons.length || right.消耗 - left.消耗);
  return {
    date,
    hasData: dailyRows.length > 0,
    hasAccountData: dailyRows.some((row) => Array.isArray(row.账户列表)),
    accountCount: accounts.length,
    total: items.length,
    items,
  };
}

function buildAnalysis(start = "", end = "") {
  const filtered = rows.filter((row) => (!start || row.日期 >= start) && (!end || row.日期 <= end));
  const byProject = aggregate(filtered, "项目");
  const summary = Object.fromEntries(numericFields.map((field) => [field, Number(byProject.reduce((total, item) => total + item[field], 0).toFixed(2))]));
  summary.现金利润 = Number((summary.预估佣金 - summary.现金消耗).toFixed(2));
  summary.ROI = summary.消耗 ? Number((summary.预估佣金 / summary.消耗).toFixed(4)) : 0;
  summary.现金ROI = summary.现金消耗 ? Number((summary.预估佣金 / summary.现金消耗).toFixed(4)) : 0;
  return {
    cachedAt,
    rows: filtered.length,
    range: filtered.length ? [filtered.reduce((min, row) => row.日期 < min ? row.日期 : min, filtered[0].日期), filtered.reduce((max, row) => row.日期 > max ? row.日期 : max, filtered[0].日期)] : ["-", "-"],
    summary,
    alerts: buildDhhAlerts(rows),
    by_optimizer: aggregate(filtered, "优化师"),
    by_project: byProject,
    by_date: aggregate(filtered, "日期").sort((left, right) => right.日期.localeCompare(left.日期)),
    by_task: aggregate(filtered, "任务名"),
    by_optimizer_date: aggregate(filtered, ["日期", "优化师"]),
    by_optimizer_task_date: aggregate(filtered, ["日期", "优化师", "任务名"]),
    by_project_date: aggregate(filtered, ["日期", "项目"]),
    by_task_date: aggregate(filtered, ["日期", "任务名"]),
  };
}

function jdMetrics(values) {
  const spend = values.消耗 || 0;
  const billableConversions = values.计费转化数 || 0;
  const estimatedCommission = (values.首购预估佣金 || 0) + (values.回流预估佣金 || 0);
  const actualCommission = (values.首购实际佣金 || 0) + (values.回流实际佣金 || 0);
  const compensation = values.条件内预估赔付金额 || 0;
  return {
    ...Object.fromEntries(Object.entries(values).map(([key, value]) => [key, Number(value.toFixed(2))])),
    转化成本: billableConversions ? Number((spend / billableConversions).toFixed(2)) : 0,
    预估佣金合计: Number(estimatedCommission.toFixed(2)),
    预估利润: Number((estimatedCommission + compensation - spend).toFixed(2)),
    预估ROI: spend ? Number(((estimatedCommission + compensation) / spend).toFixed(4)) : 0,
    实际佣金合计: Number(actualCommission.toFixed(2)),
    实际利润: Number((actualCommission + compensation - spend).toFixed(2)),
    实际ROI: spend ? Number(((actualCommission + compensation) / spend).toFixed(4)) : 0,
  };
}

function aggregateJd(data, fields) {
  const groupFields = Array.isArray(fields) ? fields : [fields];
  const buckets = new Map();
  for (const row of data) {
    const key = groupFields.map((field) => row[field]).join("\u0001");
    if (!buckets.has(key)) {
      buckets.set(key, {
        dimensions: Object.fromEntries(groupFields.map((field) => [field, row[field]])),
        values: Object.fromEntries(jdNumericFields.map((field) => [field, 0])),
      });
    }
    const bucket = buckets.get(key).values;
    jdNumericFields.forEach((field) => { bucket[field] += row[field]; });
  }
  return [...buckets.values()]
    .map(({ dimensions, values }) => ({ ...dimensions, ...jdMetrics(values) }))
    .sort((left, right) => right.消耗 - left.消耗);
}

function buildJdAnalysis(start = "", end = "") {
  const filtered = jdRows.filter((row) => (!start || row.日期 >= start) && (!end || row.日期 <= end));
  const emptyValues = Object.fromEntries(jdNumericFields.map((field) => [field, 0]));
  const summary = aggregateJd(filtered, [])[0] || jdMetrics(emptyValues);
  return {
    cachedAt: jdCachedAt,
    rows: filtered.length,
    range: filtered.length
      ? [
          filtered.reduce((min, row) => row.日期 < min ? row.日期 : min, filtered[0].日期),
          filtered.reduce((max, row) => row.日期 > max ? row.日期 : max, filtered[0].日期),
        ]
      : ["-", "-"],
    summary,
    by_optimizer: aggregateJd(filtered, "优化师"),
    by_date: aggregateJd(filtered, "日期").sort((left, right) => right.日期.localeCompare(left.日期)),
    by_media: aggregateJd(filtered, "媒体"),
    by_account: aggregateJd(filtered, "媒体账户名称"),
    by_promoter: aggregateJd(filtered, "推客用户名"),
    by_optimizer_date: aggregateJd(filtered, ["日期", "优化师"]),
  };
}

function sendJson(response, payload, status = 200) {
  const body = Buffer.from(JSON.stringify(payload));
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": body.length });
  response.end(body);
}

async function readRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

async function serveFile(response, filename, contentType) {
  const content = await fs.readFile(path.join(appDir, filename));
  response.writeHead(200, { "Content-Type": contentType, "Content-Length": content.length });
  response.end(content);
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === "GET" && request.url === "/") return serveFile(response, "index.html", "text/html; charset=utf-8");
    if (request.method === "GET" && (request.url === "/jd" || request.url === "/jd.html")) return serveFile(response, "jd.html", "text/html; charset=utf-8");
    if (request.method === "GET" && request.url === "/echarts.min.js") return serveFile(response, "echarts.min.js", "application/javascript; charset=utf-8");
    if (request.method === "GET" && request.url === "/api/current") {
      await restoreCache();
      return sendJson(response, buildAnalysis());
    }
    if (request.method === "POST" && request.url === "/api/load") {
      const payload = await readRequestBody(request);
      rows = await fetchRows(payload.token || "", payload.userId || "20");
      await saveCache();
      return sendJson(response, buildAnalysis());
    }
    if (request.method === "POST" && request.url === "/api/analyze") {
      await restoreCache();
      const payload = await readRequestBody(request);
      return sendJson(response, buildAnalysis(payload.start || "", payload.end || ""));
    }
    if (request.method === "GET" && request.url === "/api/jd/current") {
      await restoreJdCache();
      return sendJson(response, buildJdAnalysis());
    }
    if (request.method === "POST" && request.url === "/api/jd/load") {
      const payload = await readRequestBody(request);
      jdRows = await fetchJdRows(payload.token || "", payload.userId || "20");
      await saveJdCache();
      return sendJson(response, buildJdAnalysis());
    }
    if (request.method === "POST" && request.url === "/api/jd/analyze") {
      await restoreJdCache();
      const payload = await readRequestBody(request);
      return sendJson(response, buildJdAnalysis(payload.start || "", payload.end || ""));
    }
    response.writeHead(404);
    response.end("Not found");
  } catch (error) {
    sendJson(response, { error: error instanceof Error ? error.message : "服务异常" }, 400);
  }
});

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  server.listen(port, host, () => console.log(`营销日报分析系统已启动：http://${host}:${port}`));
}

export { aggregateJd, buildDhhAlerts, jdMetrics, parseCsv, parseDhhAccounts, parseJdCsv, previousBeijingDate };
