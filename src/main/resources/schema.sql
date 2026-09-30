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

-- ============================================================
-- 知识库文档清单表 knowledge_document
-- 与向量库（Redis Stack）配合：向量存 Redis，清单存 MySQL
-- 作用：文档列表展示、按 docId 删除时定位物理文件、分片数统计
-- 本文件由应用启动时自动执行，使用 IF NOT EXISTS 保证幂等
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_document (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id       VARCHAR(64)  NOT NULL                COMMENT '业务文档ID(UUID)',
    file_name    VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    file_type    VARCHAR(16)  NOT NULL                COMMENT '文件类型:txt/md/pdf',
    chunk_count  INT          NOT NULL DEFAULT 0      COMMENT '分片数',
    storage_path VARCHAR(512)          DEFAULT NULL   COMMENT '落盘路径',
    is_deleted   TINYINT      NOT NULL DEFAULT 0      COMMENT '软删除:0未删 1已删',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_id (doc_id),
    KEY idx_file_name (file_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '知识库文档清单表';
