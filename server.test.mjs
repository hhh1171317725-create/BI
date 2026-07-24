import test from "node:test";
import assert from "node:assert/strict";
import { buildDhhAlerts, parseDhhAccounts } from "./server.mjs";

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
    任务名: "淘宝闲鱼促活",
    注册数: 0,
    结算数: 8,
    账户列表: [{
      账户ID: "86784411",
      账户名称: "0723.1亿典闲鱼",
      消耗: 120,
    }],
  }], "2026-07-23");

  assert.equal(result.hasAccountData, true);
  assert.equal(result.total, 1);
  assert.equal(result.items[0].账户名称, "0723.1亿典闲鱼");
  assert.equal(result.items[0].账户ID, "86784411");
  assert.equal(result.items[0].任务名, "淘宝闲鱼促活");
  assert.equal(result.items[0].闲鱼任务, true);
  assert.equal(result.items[0].reasons.length, 1);
  assert.equal(result.items[0].reasons[0].code, "spend_without_registration");
  assert.equal(result.items[0].账户名称 === "字节", false);
});

test("settlement warning only fires when settlements are over 10 percent below registrations", () => {
  const account = {
    账户ID: "1",
    账户名称: "闲鱼账户",
    消耗: 10,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 任务名: "闲鱼任务A", 注册数: 100, 结算数: 91, 账户列表: [account] },
    { 日期: "2026-07-23", 任务名: "闲鱼任务B", 注册数: 100, 结算数: 89, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 1);
  assert.equal(result.items[0].任务名, "闲鱼任务B");
  assert.equal(result.items[0].reasons[0].code, "settlements_below_registrations_10pct");
});

test("alerts are separated by account and task", () => {
  const account = {
    账户ID: "1",
    账户名称: "闲鱼账户",
    消耗: 120,
  };
  const result = buildDhhAlerts([
    { 日期: "2026-07-23", 任务名: "闲鱼任务A", 注册数: 0, 结算数: 0, 账户列表: [account] },
    { 日期: "2026-07-23", 任务名: "闲鱼任务B", 注册数: 0, 结算数: 0, 账户列表: [account] },
  ], "2026-07-23");

  assert.equal(result.total, 2);
  assert.deepEqual(result.items.map((item) => item.任务名).sort(), ["闲鱼任务A", "闲鱼任务B"]);
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
