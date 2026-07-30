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
