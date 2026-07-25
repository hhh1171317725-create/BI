import { createHash } from "node:crypto";
import mysql from "mysql2/promise";

const dhhColumns = [
  "business_date", "media", "optimizer", "project_name", "task_name", "account_info",
  "spend", "cash_spend", "reward_spend", "estimated_commission", "settlement_count",
  "conversion_count", "registration_count", "row_hash",
];

const jdColumns = [
  "business_date", "promotion_id", "promotion_name", "media", "media_account_id",
  "media_account_name", "promoter_username", "optimizer", "conversion_count",
  "billable_conversion_count", "deduplicated_order_count", "first_purchase_order_count",
  "return_order_count", "first_purchase_effective_orders", "return_effective_orders",
  "first_purchase_invalid_orders", "return_invalid_orders", "first_purchase_completed_orders",
  "return_completed_orders", "spend", "estimated_compensation",
  "first_purchase_estimated_commission", "return_estimated_commission",
  "first_purchase_actual_commission", "return_actual_commission", "row_hash",
];

function rowHash(row) {
  return createHash("sha256").update(JSON.stringify(row)).digest("hex");
}

function dhhValues(row) {
  return [
    row.日期,
    row.媒体,
    row.优化师,
    row.项目,
    row.任务名,
    JSON.stringify(Array.isArray(row.账户列表) ? row.账户列表 : []),
    row.消耗,
    row.现金消耗,
    row.赠款消耗,
    row.预估佣金,
    row.结算数,
    row.转化数,
    row.注册数,
    rowHash(row),
  ];
}

function jdValues(row) {
  return [
    row.日期,
    row.推广位ID,
    row.推广位名称,
    row.媒体,
    row.媒体账户ID,
    row.媒体账户名称,
    row.推客用户名,
    row.优化师,
    row.转化数,
    row.计费转化数,
    row.去重订单总数,
    row.首购订单总数,
    row.回流订单总数,
    row.首购有效订单数,
    row.回流有效订单数,
    row.首购无效订单数,
    row.回流无效订单数,
    row.首购已完成订单,
    row.回流已完成订单,
    row.消耗,
    row.条件内预估赔付金额,
    row.首购预估佣金,
    row.回流预估佣金,
    row.首购实际佣金,
    row.回流实际佣金,
    rowHash(row),
  ];
}

function uniqueValues(data, mapper) {
  const valuesByHash = new Map();
  for (const row of data) {
    const values = mapper(row);
    valuesByHash.set(values[values.length - 1], values);
  }
  return [...valuesByHash.values()];
}

function parseAccountInfo(value) {
  if (Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(String(value || "[]"));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function databaseConfig(environment = process.env) {
  return {
    host: String(environment.MYSQL_HOST || "127.0.0.1"),
    port: Number(environment.MYSQL_PORT || 3306),
    database: String(environment.MYSQL_DATABASE || "BI"),
    user: String(environment.MYSQL_USER || "BI"),
    password: String(environment.MYSQL_PASSWORD || ""),
  };
}

class ReportDatabase {
  constructor(environment = process.env) {
    const config = databaseConfig(environment);
    if (!config.password) {
      throw new Error("未配置 MYSQL_PASSWORD，请在服务器 .runtime/mysql.env 中设置数据库连接");
    }
    this.pool = mysql.createPool({
      ...config,
      waitForConnections: true,
      connectionLimit: Number(environment.MYSQL_CONNECTION_LIMIT || 5),
      queueLimit: 0,
      charset: "utf8mb4",
      decimalNumbers: true,
      dateStrings: true,
      enableKeepAlive: true,
    });
  }

  async ping() {
    const connection = await this.pool.getConnection();
    try {
      await connection.ping();
      await this.ensureJdRatioTable(connection);
    } finally {
      connection.release();
    }
  }

  async ensureJdRatioTable(connection = this.pool) {
    await connection.query(
      `CREATE TABLE IF NOT EXISTS jd_account_ratios (
        account_id VARCHAR(100) NOT NULL COMMENT '京东媒体账户ID，对应日报媒体账户ID',
        account_name VARCHAR(500) NOT NULL DEFAULT '' COMMENT '京东媒体账户名称',
        config_ratio DECIMAL(8, 2) NOT NULL DEFAULT 0 COMMENT 'API返回的扣量比例configRatio，数值15表示15%',
        callback_event_type INT NOT NULL DEFAULT 0 COMMENT '回传事件类型，优先保存订单事件4',
        status TINYINT NOT NULL DEFAULT 0 COMMENT '策略状态，1为启用',
        source_updated_at BIGINT NULL COMMENT '京东策略更新时间戳（毫秒）',
        fetched_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本系统最近拉取时间',
        PRIMARY KEY (account_id),
        KEY idx_jd_ratio_account_name (account_name(191))
      ) ENGINE=InnoDB COMMENT='京东媒体账户扣量比例配置'`,
    );
  }

  async replaceJdAccountRatios(rows) {
    if (!Array.isArray(rows) || rows.length === 0) throw new Error("京东扣量比例数据为空，已取消数据库覆盖");
    const connection = await this.pool.getConnection();
    try {
      await this.ensureJdRatioTable(connection);
      await connection.beginTransaction();
      await connection.query("DELETE FROM `jd_account_ratios`");
      await this.insertChunks(
        connection,
        "jd_account_ratios",
        ["account_id", "account_name", "config_ratio", "callback_event_type", "status", "source_updated_at"],
        rows.map((row) => [
          row.accountId,
          row.accountName,
          row.configRatio,
          row.callbackEventType,
          row.status,
          row.updateTime || null,
        ]),
      );
      await connection.commit();
    } catch (error) {
      await connection.rollback();
      throw error;
    } finally {
      connection.release();
    }
  }

  async insertChunks(connection, table, columns, values) {
    for (let offset = 0; offset < values.length; offset += 500) {
      const chunk = values.slice(offset, offset + 500);
      await connection.query(
        `INSERT INTO \`${table}\` (${columns.map((column) => `\`${column}\``).join(", ")}) VALUES ?`,
        [chunk],
      );
    }
  }

  async recordFailedRun(reportType, triggerType, error) {
    await this.pool.execute(
      `INSERT INTO report_sync_runs
        (report_type, trigger_type, status, started_at, finished_at, row_count, error_message)
       VALUES (?, ?, 'failed', NOW(3), NOW(3), 0, ?)`,
      [reportType, triggerType, String(error?.message || error || "未知错误").slice(0, 1000)],
    ).catch(() => {});
  }

  async replaceOne(reportType, data, triggerType = "manual") {
    if (!Array.isArray(data) || data.length === 0) throw new Error("全量数据为空，已取消数据库覆盖");
    const isDhh = reportType === "dhh";
    const table = isDhh ? "dhh_daily_rows" : "jd_daily_rows";
    const columns = isDhh ? dhhColumns : jdColumns;
    const values = uniqueValues(data, isDhh ? dhhValues : jdValues);
    const connection = await this.pool.getConnection();
    try {
      await connection.beginTransaction();
      const [run] = await connection.execute(
        `INSERT INTO report_sync_runs
          (report_type, trigger_type, status, started_at)
         VALUES (?, ?, 'running', NOW(3))`,
        [reportType, triggerType],
      );
      await connection.query(`DELETE FROM \`${table}\``);
      await this.insertChunks(connection, table, columns, values);
      await connection.execute(
        `UPDATE report_sync_runs
         SET status = 'success', finished_at = NOW(3), row_count = ?
         WHERE id = ?`,
        [values.length, run.insertId],
      );
      await connection.commit();
    } catch (error) {
      await connection.rollback();
      await this.recordFailedRun(reportType, triggerType, error);
      throw error;
    } finally {
      connection.release();
    }
  }

  async replaceAll(dhhRows, jdRows, triggerType = "scheduled") {
    if (!dhhRows.length || !jdRows.length) throw new Error("任一全量报表为空，已取消数据库覆盖");
    const connection = await this.pool.getConnection();
    try {
      await connection.beginTransaction();
      const [run] = await connection.execute(
        `INSERT INTO report_sync_runs
          (report_type, trigger_type, status, started_at)
         VALUES ('all', ?, 'running', NOW(3))`,
        [triggerType],
      );
      await connection.query("DELETE FROM `dhh_daily_rows`");
      await connection.query("DELETE FROM `jd_daily_rows`");
      const dhhInsertValues = uniqueValues(dhhRows, dhhValues);
      const jdInsertValues = uniqueValues(jdRows, jdValues);
      await this.insertChunks(connection, "dhh_daily_rows", dhhColumns, dhhInsertValues);
      await this.insertChunks(connection, "jd_daily_rows", jdColumns, jdInsertValues);
      await connection.execute(
        `UPDATE report_sync_runs
         SET status = 'success', finished_at = NOW(3), row_count = ?
         WHERE id = ?`,
        [dhhInsertValues.length + jdInsertValues.length, run.insertId],
      );
      await connection.commit();
    } catch (error) {
      await connection.rollback();
      await this.recordFailedRun("all", triggerType, error);
      throw error;
    } finally {
      connection.release();
    }
  }

  async readDhhRows() {
    const [records] = await this.pool.query(
      `SELECT
        business_date, media, optimizer, project_name, task_name, account_info,
        spend, cash_spend, reward_spend, estimated_commission, settlement_count,
        conversion_count, registration_count
       FROM dhh_daily_rows`,
    );
    return records.map((record) => ({
      日期: String(record.business_date).slice(0, 10),
      媒体: record.media,
      优化师: record.optimizer,
      项目: record.project_name,
      任务名: record.task_name,
      账户列表: parseAccountInfo(record.account_info),
      消耗: Number(record.spend),
      现金消耗: Number(record.cash_spend),
      赠款消耗: Number(record.reward_spend),
      预估佣金: Number(record.estimated_commission),
      结算数: Number(record.settlement_count),
      转化数: Number(record.conversion_count),
      注册数: Number(record.registration_count),
    }));
  }

  async readJdRows() {
    await this.ensureJdRatioTable();
    const [records] = await this.pool.query(
      `SELECT
        j.business_date, j.promotion_id, j.promotion_name, j.media, j.media_account_id,
        j.media_account_name, j.promoter_username, j.optimizer, j.conversion_count,
        billable_conversion_count, deduplicated_order_count, first_purchase_order_count,
        return_order_count, first_purchase_effective_orders, return_effective_orders,
        first_purchase_invalid_orders, return_invalid_orders, first_purchase_completed_orders,
        return_completed_orders, spend, estimated_compensation,
        first_purchase_estimated_commission, return_estimated_commission,
        first_purchase_actual_commission, return_actual_commission,
        COALESCE(
          ratio.config_ratio,
          (
            SELECT fallback_ratio.config_ratio
            FROM jd_account_ratios AS fallback_ratio
            WHERE fallback_ratio.account_name = j.media_account_name
            ORDER BY fallback_ratio.status DESC, fallback_ratio.source_updated_at DESC
            LIMIT 1
          )
        ) AS config_ratio
       FROM jd_daily_rows AS j
       LEFT JOIN jd_account_ratios AS ratio
         ON ratio.account_id = j.media_account_id`,
    );
    return records.map((record) => ({
      日期: String(record.business_date).slice(0, 10),
      推广位ID: record.promotion_id,
      推广位名称: record.promotion_name,
      媒体: record.media,
      媒体账户ID: record.media_account_id,
      媒体账户名称: record.media_account_name,
      推客用户名: record.promoter_username,
      优化师: record.optimizer,
      扣量比例: record.config_ratio === null || record.config_ratio === undefined
        ? null
        : Number(record.config_ratio),
      转化数: Number(record.conversion_count),
      计费转化数: Number(record.billable_conversion_count),
      去重订单总数: Number(record.deduplicated_order_count),
      首购订单总数: Number(record.first_purchase_order_count),
      回流订单总数: Number(record.return_order_count),
      首购有效订单数: Number(record.first_purchase_effective_orders),
      回流有效订单数: Number(record.return_effective_orders),
      首购无效订单数: Number(record.first_purchase_invalid_orders),
      回流无效订单数: Number(record.return_invalid_orders),
      首购已完成订单: Number(record.first_purchase_completed_orders),
      回流已完成订单: Number(record.return_completed_orders),
      消耗: Number(record.spend),
      条件内预估赔付金额: Number(record.estimated_compensation),
      首购预估佣金: Number(record.first_purchase_estimated_commission),
      回流预估佣金: Number(record.return_estimated_commission),
      首购实际佣金: Number(record.first_purchase_actual_commission),
      回流实际佣金: Number(record.return_actual_commission),
    }));
  }

  async latestSyncTime(reportType) {
    const [records] = await this.pool.execute(
      `SELECT DATE_FORMAT(MAX(finished_at), '%Y-%m-%dT%H:%i:%s') AS cachedAt
       FROM report_sync_runs
       WHERE status = 'success' AND report_type IN (?, 'all')`,
      [reportType],
    );
    return records[0]?.cachedAt || "";
  }
}

let database;

function getDatabase(environment = process.env) {
  if (!database) database = new ReportDatabase(environment);
  return database;
}

export {
  ReportDatabase,
  databaseConfig,
  dhhValues,
  getDatabase,
  jdValues,
  rowHash,
  uniqueValues,
};
