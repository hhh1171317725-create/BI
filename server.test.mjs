import test from "node:test";
import assert from "node:assert/strict";
import {
  databaseConfig,
  dhhValues,
  jdValues,
  rowHash,
  uniqueValues,
} from "./database-store.mjs";
import {
  buildDhhAlerts,
  buildJdAnalysis,
  buildPetBottomData,
  beijingMonthStart,
  filterJdRows,
  isUnknownOptimizer,
  jdMetrics,
  localPetReply,
  nextBeijingNine,
  parseDhhAccounts,
  parseJdCsv,
  parseEnvironmentFile,
  resolveAiProvider,
  createSessionToken,
  validateCredentials,
  verifySessionToken,
} from "./server.mjs";

test("MySQL configuration uses environment values without embedding credentials", () => {
  assert.deepEqual(databaseConfig({
    MYSQL_HOST: "db.internal",
    MYSQL_PORT: "3307",
    MYSQL_DATABASE: "BI",
    MYSQL_USER: "bi_app",
    MYSQL_PASSWORD: "secret",
  }), {
    host: "db.internal",
    port: 3307,
    database: "BI",
    user: "bi_app",
    password: "secret",
  });
});

test("runtime env parser supports comments, quoted passwords, and equals signs", () => {
  assert.deepEqual(parseEnvironmentFile(`
    # MySQL connection
    MYSQL_HOST=127.0.0.1
    MYSQL_PASSWORD="p@ss=word#2026"
    invalid-key=ignored
  `), {
    MYSQL_HOST: "127.0.0.1",
    MYSQL_PASSWORD: "p@ss=word#2026",
  });
});

test("DHH source rows map to MySQL columns with account JSON and stable hash", () => {
  const row = {
    日期: "2026-07-25",
    媒体: "字节",
    优化师: "陈灵灿",
    项目: "淘宝促购CVR",
    任务名: "任务A",
    账户列表: [{ 账户ID: "1", 账户名称: "账户A", 消耗: 100 }],
    消耗: 100,
    现金消耗: 80,
    赠款消耗: 20,
    预估佣金: 120,
    结算数: 10,
    转化数: 12,
    注册数: 11,
  };
  const values = dhhValues(row);
  assert.equal(values[0], "2026-07-25");
  assert.deepEqual(JSON.parse(values[5]), row.账户列表);
  assert.equal(values[13], rowHash(row));
  assert.equal(values[13].length, 64);
});

test("JD source rows map all order and commission fields to MySQL", () => {
  const row = {
    日期: "2026-07-25",
    推广位ID: "P1",
    推广位名称: "推广位A",
    媒体: "京东",
    媒体账户ID: "A1",
    媒体账户名称: "账户A",
    推客用户名: "推客A",
    优化师: "陈灵灿",
    转化数: 1,
    计费转化数: 2,
    去重订单总数: 3,
    首购订单总数: 4,
    回流订单总数: 5,
    首购有效订单数: 6,
    回流有效订单数: 7,
    首购无效订单数: 8,
    回流无效订单数: 9,
    首购已完成订单: 10,
    回流已完成订单: 11,
    消耗: 12,
    条件内预估赔付金额: 13,
    首购预估佣金: 14,
    回流预估佣金: 15,
    首购实际佣金: 16,
    回流实际佣金: 17,
  };
  const values = jdValues(row);
  assert.equal(values.length, 26);
  assert.deepEqual(values.slice(8, 25), Array.from({ length: 17 }, (_, index) => index + 1));
  assert.equal(values[25], rowHash(row));
});

test("MySQL full import removes exact duplicate source rows", () => {
  const row = { 日期: "2026-07-25", 优化师: "陈灵灿", 消耗: 100 };
  const values = uniqueValues([row, { ...row }, { ...row, 消耗: 101 }], dhhValues);
  assert.equal(values.length, 2);
});

test("login accepts only the configured report credentials", () => {
  assert.equal(validateCredentials("hhh", "123456"), true);
  assert.equal(validateCredentials("hhh", "wrong"), false);
  assert.equal(validateCredentials("other", "123456"), false);
});

test("signed login sessions reject tampering and expiry", () => {
  const now = Date.now();
  const token = createSessionToken(now);
  assert.equal(verifySessionToken(token, now), true);
  assert.equal(verifySessionToken(`${token}changed`, now), false);
  assert.equal(verifySessionToken(token, now + (8 * 24 * 60 * 60 * 1000)), false);
});

test("daily refresh schedules the next 09:00 in Beijing", () => {
  assert.equal(
    nextBeijingNine(new Date("2026-07-25T00:30:00.000Z")).toISOString(),
    "2026-07-25T01:00:00.000Z",
  );
  assert.equal(
    nextBeijingNine(new Date("2026-07-25T01:00:00.000Z")).toISOString(),
    "2026-07-26T01:00:00.000Z",
  );
  assert.equal(
    nextBeijingNine(new Date("2026-12-31T18:00:00.000Z")).toISOString(),
    "2027-01-01T01:00:00.000Z",
  );
});

test("default report range starts at the current Beijing month", () => {
  assert.equal(beijingMonthStart(new Date("2026-07-31T15:59:59.000Z")), "2026-07-01");
  assert.equal(beijingMonthStart(new Date("2026-07-31T16:00:00.000Z")), "2026-08-01");
  assert.equal(beijingMonthStart(new Date("2026-12-31T16:00:00.000Z")), "2027-01-01");
});

test("parseDhhAccounts reads account identity and spend from account detail JSON", () => {
  const accounts = parseDhhAccounts(JSON.stringify([{
    media_ad_account_id: "86784411",
    media_ad_account_name: "0723.1亿典闲鱼",
    media_total_cost: "123.45",
    media_cash_cost: "100.00",
    media_reward_cost: "23.45",
  }]));
  assert.deepEqual(accounts, [{
    账户ID: "86784411",
    账户名称: "0723.1亿典闲鱼",
    消耗: 123.45,
    现金消耗: 100,
    赠款消耗: 23.45,
  }]);
});

test("buildDhhAlerts monitors real accounts instead of media names", () => {
  const result = buildDhhAlerts([{
    日期: "2026-07-23",
    媒体: "字节",
    任务名: "淘宝促购CVR",
    注册数: 0,
    结算数: 8,
    账户列表: [{
      账户ID: "86784411",
      账户名称: "0723.1亿典促购",
      消耗: 120,
    }],
  }], "2026-07-23");

  assert.equal(result.hasAccountData, true);
  assert.equal(result.total, 1);
  assert.equal(result.items[0].账户名称, "0723.1亿典促购");
  assert.equal(result.items[0].账户ID, "86784411");
  assert.equal(result.items[0].项目, "淘宝促购CVR");
  assert.equal(result.items[0].任务名, "淘宝促购CVR");
  assert.equal(result.items[0].闲鱼任务, false);
  assert.equal(result.items[0].reasons.length, 1);
  assert.equal(result.items[0].reasons[0].code, "spend_without_registration");
  assert.equal(result.items[0].账户名称 === "字节", false);
});

test("settlement warning only fires when settlements are over 10 percent below registrations", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 10,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 任务名: "普通任务A", 注册数: 100, 结算数: 91, 账户列表: [account] },
    { 日期: "2026-07-23", 任务名: "普通任务B", 注册数: 100, 结算数: 89, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 1);
  assert.equal(result.items[0].任务名, "普通任务B");
  assert.equal(result.items[0].reasons[0].code, "settlements_below_registrations_10pct");
});

test("alerts are separated by account and task", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 120,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 任务名: "普通任务A", 注册数: 0, 结算数: 0, 账户列表: [account] },
    { 日期: "2026-07-23", 任务名: "普通任务B", 注册数: 0, 结算数: 0, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 2);
  assert.deepEqual(result.items.map((item) => item.任务名).sort(), ["普通任务A", "普通任务B"]);
});

test("alerts are separated by selected optimizer", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 120,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 优化师: "优化师A", 任务名: "普通任务", 注册数: 0, 结算数: 0, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师B", 任务名: "普通任务", 注册数: 0, 结算数: 0, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 2);
  assert.deepEqual(result.items.map((item) => item.优化师).sort(), ["优化师A", "优化师B"]);
});

test("alerts and filter options are separated by project", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 120,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 优化师: "优化师A", 项目: "项目A", 任务名: "普通任务", 注册数: 0, 结算数: 0, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师A", 项目: "项目B", 任务名: "普通任务", 注册数: 0, 结算数: 0, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 2);
  assert.deepEqual(result.items.map((item) => item.项目).sort(), ["项目A", "项目B"]);
});

test("alert filter options include optimizers and tasks without anomalies", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 10,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 优化师: "优化师A", 项目: "项目A", 任务名: "正常任务", 注册数: 100, 结算数: 100, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师A", 项目: "项目B", 任务名: "异常任务", 注册数: 100, 结算数: 80, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师B", 项目: "项目C", 任务名: "无异常任务", 注册数: 100, 结算数: 100, 账户列表: [account] },
  ], "2026-07-23");

  assert.deepEqual(result.filterOptions, [
    { 优化师: "优化师A", 项目: "项目A", 任务名: "正常任务" },
    { 优化师: "优化师A", 项目: "项目B", 任务名: "异常任务" },
    { 优化师: "优化师B", 项目: "项目C", 任务名: "无异常任务" },
  ]);
  assert.equal(result.total, 1);
});

test("Xianyu accounts and tasks are excluded from alerts", () => {
  const result = buildDhhAlerts([
    {
      日期: "2026-07-23",
      任务名: "淘宝闲鱼促活",
      注册数: 0,
      结算数: 0,
      账户列表: [{ 账户ID: "1", 账户名称: "普通账户", 消耗: 120 }],
    },
    {
      日期: "2026-07-23",
      任务名: "普通任务",
      注册数: 0,
      结算数: 0,
      账户列表: [{ 账户ID: "2", 账户名称: "咸鱼账户", 消耗: 120 }],
    },
  ], "2026-07-23");

  assert.equal(result.total, 0);
  assert.equal(result.accountCount, 0);
});

test("buildDhhAlerts rejects stale media-level cache as account data", () => {
  const result = buildDhhAlerts([{
    日期: "2026-07-23",
    媒体: "字节",
    账户: "字节",
    消耗: 100,
    注册数: 0,
    结算数: 0,
  }], "2026-07-23");

  assert.equal(result.hasData, true);
  assert.equal(result.hasAccountData, false);
  assert.equal(result.accountCount, 0);
  assert.equal(result.total, 0);
});

test("JD unknown optimizer filter applies to every downstream dataset", () => {
  const data = [
    { 日期: "2026-07-22", 优化师: "优化师A" },
    { 日期: "2026-07-23", 优化师: "优化师A" },
    { 日期: "2026-07-23", 优化师: "未填写" },
    { 日期: "2026-07-23", 优化师: "未知优化师" },
    { 日期: "2026-07-23", 优化师: "unknown" },
  ];

  assert.equal(isUnknownOptimizer("未知"), true);
  assert.equal(isUnknownOptimizer("优化师A"), false);
  assert.deepEqual(
    filterJdRows(data, "2026-07-23", "2026-07-23", true),
    [{ 日期: "2026-07-23", 优化师: "优化师A" }],
  );
  assert.equal(filterJdRows(data, "2026-07-23", "2026-07-23", false).length, 4);
});

test("JD effective orders combine first-purchase and returning effective orders", () => {
  const result = jdMetrics({
    首购有效订单数: 12,
    回流有效订单数: 8,
  });

  assert.equal(result.有效订单数, 20);
});

test("JD analysis exposes account identity and account-date drilldown rows", () => {
  const rows = parseJdCsv([
    "业务日期,媒体账户ID,媒体账户名称,优化师,消耗,首购预估佣金",
    "2026-07-23,A-135,广州云联-京东-135,优化师A,100,120",
    "2026-07-24,A-135,广州云联-京东-135,优化师A,200,220",
  ].join("\n"));
  const result = buildJdAnalysis(rows, "2026-07-01", "2026-07-31", true);

  assert.equal(result.by_account.length, 1);
  assert.equal(result.by_account[0].媒体账户ID, "A-135");
  assert.deepEqual(result.by_account_date.map((row) => row.日期), ["2026-07-24", "2026-07-23"]);
  assert.equal(result.by_account_date[0].媒体账户名称, "广州云联-京东-135");
  assert.deepEqual(result.by_media_date.map((row) => row.日期), ["2026-07-24", "2026-07-23"]);
  assert.deepEqual(result.by_promoter_date.map((row) => row.日期), ["2026-07-24", "2026-07-23"]);
});

test("data pet answers report metrics without an AI key", () => {
  const context = {
    reportType: "京东 CPA 日报",
    range: ["2026-07-01", "2026-07-24"],
    summary: {
      消耗: 123456.78,
      有效订单数: 321,
      预估利润: 4567.89,
      实际利润: 3456.78,
      预估ROI: 1.12,
      实际ROI: 1.05,
    },
    topOptimizers: [
      { 优化师: "优化师B", 消耗: 200 },
      { 优化师: "优化师A", 消耗: 300 },
    ],
  };

  assert.match(localPetReply("有效订单有多少？", context), /321/);
  assert.match(localPetReply("分析利润和ROI", context), /预估\/现金利润 4,567\.89 元/);
  assert.match(localPetReply("哪个优化师消耗最高？", context), /1\. 优化师A：300 元/);
});

test("data pet receives question-matched bottom-table rows and full summaries", () => {
  const bottomData = buildPetBottomData("分析陈灵灿的淘宝促购CVR", {
    reportType: "大航海日报",
    range: ["2026-07-01", "2026-07-31"],
  }, [
    { 日期: "2026-07-23", 优化师: "陈灵灿", 项目: "淘宝促购CVR", 任务名: "任务A", 媒体: "字节", 消耗: 100 },
    { 日期: "2026-07-24", 优化师: "王李敏", 项目: "淘宝促购CVR", 任务名: "任务B", 媒体: "字节", 消耗: 200 },
    { 日期: "2026-06-30", 优化师: "陈灵灿", 项目: "淘宝促购CVR", 任务名: "任务C", 媒体: "字节", 消耗: 300 },
  ], []);

  assert.equal(bottomData.底表总行数, 2);
  assert.equal(bottomData.问题匹配行数, 1);
  assert.equal(bottomData.明细行[0].优化师, "陈灵灿");
  assert.equal(bottomData.维度汇总.按优化师.length, 2);
  assert.deepEqual(bottomData.匹配条件, {
    优化师: ["陈灵灿"],
    项目: ["淘宝促购CVR"],
  });
});

test("JD pet bottom table respects unknown optimizer exclusion", () => {
  const bottomData = buildPetBottomData("分析账户", {
    reportType: "京东 CPA 日报",
    range: ["2026-07-01", "2026-07-31"],
    excludeUnknownOptimizer: true,
  }, [], [
    { 日期: "2026-07-23", 优化师: "陈灵灿", 媒体账户名称: "账户A", 消耗: 100 },
    { 日期: "2026-07-23", 优化师: "未知优化师", 媒体账户名称: "账户B", 消耗: 200 },
  ]);

  assert.equal(bottomData.底表总行数, 1);
  assert.equal(bottomData.明细行[0].媒体账户名称, "账户A");
});

test("data pet resolves DeepSeek without exposing credentials to the browser", () => {
  const config = resolveAiProvider({
    AI_PROVIDER: "deepseek",
    DEEPSEEK_API_KEY: "test-secret",
  });

  assert.equal(config.provider, "deepseek");
  assert.equal(config.model, "deepseek-v4-flash");
  assert.equal(config.baseUrl, "https://api.deepseek.com");
});

test("browser AI configuration overrides missing server environment", () => {
  const config = resolveAiProvider({}, {
    provider: "deepseek",
    apiKey: "browser-secret",
  });

  assert.equal(config.provider, "deepseek");
  assert.equal(config.apiKey, "browser-secret");
  assert.equal(config.model, "deepseek-v4-flash");
});
