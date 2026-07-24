import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appDir = path.dirname(fileURLToPath(import.meta.url));
const host = process.env.DHH_HOST || "127.0.0.1";
const port = Number(process.env.DHH_PORT || 8765);
const runtimeDir = process.env.DHH_RUNTIME_DIR || path.join(appDir, ".runtime");
const cachePath = path.join(runtimeDir, "report-cache.json");
const exportUrl = "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport";
const numericFields = ["消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数"];
let rows = [];
let cachedAt = "";

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
      优化师: String(record.优化师 || "").trim() || "未填写",
      任务名: task,
      项目: projectFromTask(task),
    };
    numericFields.forEach((field) => { row[field] = number(record[field]); });
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
    by_optimizer: aggregate(filtered, "优化师"),
    by_project: byProject,
    by_date: aggregate(filtered, "日期").sort((left, right) => right.日期.localeCompare(left.日期)),
    by_task: aggregate(filtered, "任务名"),
    by_optimizer_date: aggregate(filtered, ["日期", "优化师"]),
    by_project_date: aggregate(filtered, ["日期", "项目"]),
    by_task_date: aggregate(filtered, ["日期", "任务名"]),
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
    response.writeHead(404);
    response.end("Not found");
  } catch (error) {
    sendJson(response, { error: error instanceof Error ? error.message : "服务异常" }, 400);
  }
});

server.listen(port, host, () => console.log(`大航海分析系统已启动：http://${host}:${port}`));
