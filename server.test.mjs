import test from "node:test";
import assert from "node:assert/strict";
import {
  buildDhhAlerts,
  filterJdRows,
  isUnknownOptimizer,
  jdMetrics,
  parseDhhAccounts,
} from "./server.mjs";

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

test("alert filter options include optimizers and tasks without anomalies", () => {
  const account = {
    账户ID: "1",
    账户名称: "普通账户",
    消耗: 10,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 优化师: "优化师A", 任务名: "正常任务", 注册数: 100, 结算数: 100, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师A", 任务名: "异常任务", 注册数: 100, 结算数: 80, 账户列表: [account] },
    { 日期: "2026-07-23", 优化师: "优化师B", 任务名: "无异常任务", 注册数: 100, 结算数: 100, 账户列表: [account] },
  ], "2026-07-23");

  assert.deepEqual(result.filterOptions, [
    { 优化师: "优化师A", 任务列表: ["异常任务", "正常任务"] },
    { 优化师: "优化师B", 任务列表: ["无异常任务"] },
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
