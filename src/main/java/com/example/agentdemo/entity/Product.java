package com.example.agentdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体 —— 传统 CRUD 的领域对象，同时也是 Agent 工具的操作对象。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal price;

    private Integer stock;

    private String description;

    /**
     * 逻辑删除标记，配合 application.yml 中 logic-delete-field 使用。
     * MyBatis-Plus 会自动为所有查询追加 AND is_deleted = 0，删除则转为 UPDATE。
     */
    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
