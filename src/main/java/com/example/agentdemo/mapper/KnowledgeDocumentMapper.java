package com.example.agentdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agentdemo.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档清单 Mapper。
 * 由启动类的 @MapperScan("com.example.agentdemo.mapper") 统一扫描。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
