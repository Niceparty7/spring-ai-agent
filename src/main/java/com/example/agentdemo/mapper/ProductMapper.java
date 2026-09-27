package com.example.agentdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agentdemo.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 零 XML 的 Mapper：单表 CRUD 全部由 BaseMapper 提供。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
