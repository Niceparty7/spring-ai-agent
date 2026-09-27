-- ============================================================
-- 商品表 product
-- 说明：数据库 agent_demo 需先手工创建（见下方注释行）
-- 本文件由应用启动时自动执行，使用 IF NOT EXISTS 保证幂等
-- ============================================================

-- CREATE DATABASE IF NOT EXISTS agent_demo
--   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(128)  NOT NULL                COMMENT '商品名称',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '价格(元)',
    stock       INT           NOT NULL DEFAULT 0      COMMENT '库存',
    description VARCHAR(512)           DEFAULT NULL   COMMENT '商品描述',
    is_deleted  TINYINT       NOT NULL DEFAULT 0      COMMENT '软删除:0未删 1已删',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';
