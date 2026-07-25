CREATE TABLE IF NOT EXISTS `report_sync_runs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_type` ENUM('dhh', 'jd', 'all') NOT NULL,
  `trigger_type` ENUM('manual', 'scheduled') NOT NULL,
  `status` ENUM('running', 'success', 'failed') NOT NULL DEFAULT 'running',
  `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` DATETIME(3) NULL,
  `row_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `error_message` VARCHAR(1000) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sync_runs_started_at` (`started_at`),
  KEY `idx_sync_runs_report_status` (`report_type`, `status`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `dhh_daily_rows` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `business_date` DATE NOT NULL,
  `media` VARCHAR(100) NOT NULL DEFAULT '',
  `optimizer` VARCHAR(255) NOT NULL DEFAULT '',
  `project_name` VARCHAR(255) NOT NULL DEFAULT '',
  `task_name` VARCHAR(500) NOT NULL DEFAULT '',
  `account_info` JSON NULL,
  `spend` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `cash_spend` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `reward_spend` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `settlement_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `registration_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dhh_row_hash` (`row_hash`),
  KEY `idx_dhh_date` (`business_date`),
  KEY `idx_dhh_optimizer_date` (`optimizer`, `business_date`),
  KEY `idx_dhh_project_date` (`project_name`, `business_date`),
  KEY `idx_dhh_task_date` (`task_name`(191), `business_date`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `jd_daily_rows` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `business_date` DATE NOT NULL,
  `promotion_id` VARCHAR(100) NOT NULL DEFAULT '',
  `promotion_name` VARCHAR(500) NOT NULL DEFAULT '',
  `media` VARCHAR(100) NOT NULL DEFAULT '',
  `media_account_id` VARCHAR(100) NOT NULL DEFAULT '',
  `media_account_name` VARCHAR(500) NOT NULL DEFAULT '',
  `promoter_username` VARCHAR(255) NOT NULL DEFAULT '',
  `optimizer` VARCHAR(255) NOT NULL DEFAULT '',
  `conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `billable_conversion_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `deduplicated_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_order_count` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_effective_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_effective_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_invalid_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_invalid_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_completed_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_completed_orders` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `spend` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `estimated_compensation` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_estimated_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `first_purchase_actual_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `return_actual_commission` DECIMAL(18, 2) NOT NULL DEFAULT 0,
  `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_jd_row_hash` (`row_hash`),
  KEY `idx_jd_date` (`business_date`),
  KEY `idx_jd_optimizer_date` (`optimizer`, `business_date`),
  KEY `idx_jd_account_date` (`media_account_name`(191), `business_date`),
  KEY `idx_jd_promoter_date` (`promoter_username`, `business_date`)
) ENGINE=InnoDB;

CREATE OR REPLACE VIEW `jd_daily_metrics` AS
SELECT
  `business_date`,
  `optimizer`,
  `media_account_name`,
  `promoter_username`,
  `spend`,
  (`first_purchase_effective_orders` + `return_effective_orders`) AS `effective_order_count`,
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
