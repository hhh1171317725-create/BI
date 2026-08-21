-- 为已有 BI 数据库增加 ClickFlare 收益快照与同步记录表。
-- 可重复执行，不会删除已有数据。

CREATE TABLE IF NOT EXISTS `clickflare_campaign_revenue_daily` (
  `business_date` DATE NOT NULL COMMENT '收益业务日期，北京时间',
  `campaign_id` VARCHAR(100) NOT NULL COMMENT 'ClickFlare收益活动ID',
  `campaign_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'ClickFlare收益活动名称',
  `conversions` BIGINT NOT NULL DEFAULT 0 COMMENT '转化数',
  `revenue` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '收益金额',
  `spend` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游回传消耗',
  `roi` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游回传ROI',
  `currency` VARCHAR(20) NOT NULL DEFAULT '' COMMENT '币种',
  `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近同步写入时间',
  PRIMARY KEY (`business_date`, `campaign_id`),
  KEY `idx_clickflare_date_revenue` (`business_date`, `revenue`),
  KEY `idx_clickflare_campaign_date` (`campaign_id`, `business_date`)
) ENGINE=InnoDB COMMENT='ClickFlare活动每日收益快照';

CREATE TABLE IF NOT EXISTS `clickflare_revenue_sync_runs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '同步记录主键',
  `business_date` DATE NOT NULL COMMENT '同步的收益日期',
  `row_count` INT NOT NULL DEFAULT 0 COMMENT '成功写入的活动数量',
  `trigger_type` VARCHAR(30) NOT NULL DEFAULT 'scheduled'
    COMMENT '触发方式：scheduled定时、manual手动',
  `finished_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步完成时间',
  PRIMARY KEY (`id`),
  KEY `idx_clickflare_sync_date` (`business_date`, `finished_at`)
) ENGINE=InnoDB COMMENT='ClickFlare收益同步记录';
