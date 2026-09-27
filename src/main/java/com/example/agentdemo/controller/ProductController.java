package com.example.agentdemo.controller;

import com.example.agentdemo.entity.Product;
import com.example.agentdemo.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 传统 CRUD 管理接口（与 AI 无关，可独立使用）。
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 列表
     */
    @GetMapping
    public List<Product> list() {
        return productService.list();
    }

    /**
     * 详情
     */
    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.getById(id);
    }

    /**
     * 新增
     */
    @PostMapping
    public Product create(@RequestBody Product product) {
        productService.save(product);
        return product;
    }

    /**
     * 修改
     */
    @PutMapping("/{id}")
    public boolean update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return productService.updateById(product);
    }

    /**
     * 删除（软删除）
     */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return productService.removeById(id);
    }
}
