package com.example.agentdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识库文档清单 —— 对应向量库中一篇文档的元信息。
 *
 * <p>分工：切片正文与其向量存于 Redis Stack，本表只存「文档级」信息，
 * 用于文档列表展示、按 docId 定位物理文件、分片数统计。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务文档 ID（UUID），与向量 metadata 中的 docId 一致。 */
    private String docId;

    private String fileName;

    /** 文件类型：txt / md / pdf。 */
    private String fileType;

    /** 该文档切分出的分片数量。 */
    private Integer chunkCount;

    /** 上传文件落盘路径，用于删除时清理物理文件。 */
    private String storagePath;

    /**
     * 逻辑删除标记，配合 application.yml 中 logic-delete-field 使用。
     * MyBatis-Plus 会自动为所有查询追加 AND is_deleted = 0，删除则转为 UPDATE。
     */
    @TableLogic
    private Integer isDeleted;

    // create_time / update_time 由数据库 DEFAULT CURRENT_TIMESTAMP 维护，Java 侧不写自动填充
}
