package com.example.agentdemo.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.example.agentdemo.entity.Product;
import com.example.agentdemo.mapper.ProductMapper;
import com.example.agentdemo.service.ProductService;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {
}
