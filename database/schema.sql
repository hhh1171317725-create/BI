CREATE TABLE IF NOT EXISTS `report_users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户自增主键',
  `username` VARCHAR(64) NOT NULL COMMENT '登录用户名',
  `password_hash` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'PBKDF2-SHA256 密码哈希',
  `role` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'user'
    COMMENT '角色：admin管理员、user普通用户',
  `active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许登录：1允许、0停用',
  `session_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '会话版本，修改密码或状态时递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
  `last_login_at` DATETIME(3) NULL COMMENT '最近登录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_users_username` (`username`),
  KEY `idx_report_users_role_active` (`role`, `active`)
) ENGINE=InnoDB COMMENT='报表系统登录用户';

CREATE TABLE IF NOT EXISTS `report_sync_runs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '同步任务自增主键',
  `report_type` ENUM('dhh', 'jd', 'jd_low_activity', 'all') NOT NULL
    COMMENT '报表类型：dhh大航海、jd京东、jd_low_activity京东低活、all全部',
  `trigger_type` ENUM('manual', 'scheduled') NOT NULL COMMENT '触发方式：manual手动、scheduled定时',
  `status` ENUM('running', 'success', 'failed') NOT NULL DEFAULT 'running' COMMENT '同步状态：运行中、成功、失败',
  `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步开始时间',
  `finished_at` DATETIME(3) NULL COMMENT '同步完成时间',
  `row_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本次同步写入或更新的底表行数',
  `error_message` VARCHAR(1000) NULL COMMENT '同步失败原因，成功时为空',
  PRIMARY KEY (`id`),
  KEY `idx_sync_runs_started_at` (`started_at`),
  KEY `idx_sync_runs_report_status` (`report_type`, `status`)
) ENGINE=InnoDB COMMENT='报表手动更新与每日定时同步执行记录';

CREATE TABLE IF NOT EXISTS `dhh_daily_rows` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '大航海底表自增主键',
  `business_date` DATE NOT NULL COMMENT '业务日期，北京时间，格式YYYY-MM-DD',
  `media` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '投放媒体名称',
  `optimizer` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '优化师姓名',
  `project_name` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '项目名称，由任务名称归类',
  `task_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '原始日报任务名称',
  `account_info` JSON NULL COMMENT '媒体账户明细JSON，包含账户ID、名称及账户消耗',
  `spend` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '总消耗，单位：元',
  `cash_spend` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '现金消耗，单位：元',
  `reward_spend` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '赠款消耗，单位：元',
  `estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '预估佣金，单位：元',
  `settlement_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '结算数量',
  `conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '转化数量',
  `registration_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '注册数量',
  `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始行SHA-256哈希，用于防止重复写入',
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近一次同步写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dhh_row_hash` (`row_hash`),
  KEY `idx_dhh_date` (`business_date`),
  KEY `idx_dhh_optimizer_date` (`optimizer`, `business_date`),
  KEY `idx_dhh_project_date` (`project_name`, `business_date`),
  KEY `idx_dhh_task_date` (`task_name`(191), `business_date`)
) ENGINE=InnoDB COMMENT='大航海日报原始底表数据';

CREATE TABLE IF NOT EXISTS `jd_daily_rows` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '京东底表自增主键',
  `business_date` DATE NOT NULL COMMENT '业务日期，北京时间，格式YYYY-MM-DD',
  `promotion_id` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '推广位ID',
  `promotion_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '推广位名称',
  `media` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '投放媒体名称',
  `media_account_id` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '媒体账户ID',
  `media_account_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '媒体账户名称',
  `promoter_username` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '推客用户名',
  `optimizer` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '优化师姓名',
  `conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '转化数量',
  `billable_conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '计费转化数量',
  `deduplicated_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '去重订单总数',
  `first_purchase_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购订单总数',
  `return_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流订单总数',
  `first_purchase_effective_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购有效订单数',
  `return_effective_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流有效订单数',
  `first_purchase_invalid_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购无效订单数',
  `return_invalid_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流无效订单数',
  `first_purchase_completed_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购已完成订单数',
  `return_completed_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流已完成订单数',
  `spend` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '总消耗，单位：元',
  `estimated_compensation` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '条件内预估赔付金额（当日），单位：元',
  `first_purchase_estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购预估佣金，单位：元',
  `return_estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流预估佣金，单位：元',
  `first_purchase_actual_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '首购实际佣金，单位：元',
  `return_actual_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '回流实际佣金，单位：元',
  `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始行SHA-256哈希，用于防止重复写入',
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近一次同步写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_jd_row_hash` (`row_hash`),
  KEY `idx_jd_date` (`business_date`),
  KEY `idx_jd_account_id_date` (`media_account_id`, `business_date`),
  KEY `idx_jd_optimizer_date` (`optimizer`, `business_date`),
  KEY `idx_jd_account_date` (`media_account_name`(191), `business_date`),
  KEY `idx_jd_promoter_date` (`promoter_username`, `business_date`)
) ENGINE=InnoDB COMMENT='京东CPA日报原始底表数据';

CREATE TABLE IF NOT EXISTS `jd_low_activity_plan_rows` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '低活明细自增主键',
  `business_date` DATE NOT NULL COMMENT '业务日期，北京时间',
  `admin_user` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '上游管理员或数据归属',
  `task_name` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '低活任务名称',
  `advertiser_id` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '媒体账户ID',
  `advertiser_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '媒体账户名称',
  `plan_id` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '计划ID；上游缺失时回退为账户ID',
  `plan_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '计划名称；上游缺失时回退为账户名称',
  `has_plan_dimension` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '上游是否返回独立计划字段',
  `spend` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '广告消耗，单位：元',
  `amount` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游amount原值',
  `impressions` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '展现量',
  `clicks` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '点击量',
  `conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '转化数',
  `successful_conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '成功转化数',
  `filtered_conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '过滤转化数',
  `valid_parent_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '有效父订单数',
  `valid_order_uv` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '有效订单UV',
  `unit_price` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游price原值',
  `valid_click_uv` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '有效点击UV',
  `commission` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '奖励订单佣金，单位：元',
  `first_day_commission` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '首日佣金，单位：元',
  `low_commission_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '低佣订单数',
  `t3_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT 'T3订单数',
  `total_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '总订单数',
  `upstream_profit` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游日利润原值',
  `upstream_simulated_profit` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游模拟利润原值',
  `profit_gap` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游利润差',
  `budgeted_gross_margin_rate` DECIMAL(18, 8) NOT NULL DEFAULT 0 COMMENT '预算毛利率',
  `gap_ratio` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '上游差值比例文本',
  `media_type` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '媒体类型',
  `league_account` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '联盟账户',
  `customer_agent` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '客户代理',
  `remark` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '上游备注',
  `raw_json` MEDIUMTEXT NULL COMMENT '完整上游JSON，供字段追溯',
  `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化行SHA-256哈希',
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近同步时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_jd_low_activity_row_hash` (`row_hash`),
  KEY `idx_jd_low_activity_date` (`business_date`),
  KEY `idx_jd_low_activity_account_date` (`advertiser_id`, `business_date`),
  KEY `idx_jd_low_activity_plan_date` (`plan_id`, `business_date`),
  KEY `idx_jd_low_activity_task_date` (`task_name`, `business_date`)
) ENGINE=InnoDB COMMENT='京东低活任务计划维度原始明细';

CREATE OR REPLACE VIEW `jd_daily_metrics` AS
SELECT
  `business_date`,
  `optimizer`,
  `media_account_name`,
  `promoter_username`,
  `spend`,
  (`first_purchase_effective_orders` + `return_effective_orders`) AS `effective_order_count`,
  (
    `first_purchase_effective_orders`
    / NULLIF(`first_purchase_effective_orders` + `return_effective_orders`, 0)
  ) AS `effective_first_purchase_rate`,
  (`first_purchase_estimated_commission` + `return_estimated_commission`) AS `estimated_commission`,
  (`first_purchase_actual_commission` + `return_actual_commission`) AS `actual_commission`,
  (
    `first_purchase_estimated_commission`
    + `return_estimated_commission`
    + `estimated_compensation`
    - `spend`
  ) AS `estimated_profit`,
  (
    `first_purchase_actual_commission`
    + `return_actual_commission`
    + `estimated_compensation`
    - `spend`
  ) AS `actual_profit`
FROM `jd_daily_rows`;
