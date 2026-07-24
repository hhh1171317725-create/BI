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
  assert.equal(result.items[0].reasons.length, 2);
  assert.equal(result.items[0].账户名称 === "字节", false);
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
