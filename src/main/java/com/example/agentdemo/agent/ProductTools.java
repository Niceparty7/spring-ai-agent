package com.example.agentdemo.agent;

import com.example.agentdemo.entity.Product;
import com.example.agentdemo.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 的「手脚」：把传统 CRUD 包装成模型可调用的工具。
 *
 * <p>两条关键设计约束：
 * <ol>
 *   <li>方法统一返回 {@link String}。框架会把返回值原样回传给模型，
 *       若直接返回含 {@code LocalDateTime} 的实体，可能触发内部 ObjectMapper 的 JavaTime 序列化问题。</li>
 *   <li>description 要写清「什么场景该调用」，模型完全依赖它做决策。</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProductTools {

    private final ProductService productService;

    @Tool(description = "查询商品列表。当用户想查看、浏览、列出、盘点商品时调用。")
    public String listProducts() {
        log.info("[Tool] listProducts");
        List<Product> list = productService.list();
        if (list == null || list.isEmpty()) {
            return "当前没有任何商品。";
        }
        return list.stream()
                .map(p -> "ID=%d 名称=%s 价格=%s元 库存=%d".formatted(p.getId(), p.getName(), p.getPrice(), p.getStock()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "根据商品ID查询单个商品详情。当用户提到具体商品ID时调用。")
    public String getProduct(@ToolParam(description = "商品ID") Long id) {
        log.info("[Tool] getProduct id={}", id);
        Product p = productService.getById(id);
        if (p == null) {
            return "未找到 ID 为 " + id + " 的商品。";
        }
        return "ID=%d 名称=%s 价格=%s元 库存=%d 描述=%s".formatted(
                p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getDescription());
    }

    @Tool(description = "新增商品。当用户要求添加、创建、上架一个商品时调用。")
    public String createProduct(@ToolParam(description = "商品名称") String name,
                                @ToolParam(description = "价格,单位元") BigDecimal price,
                                @ToolParam(description = "库存数量") Integer stock) {
        log.info("[Tool] createProduct name={} price={} stock={}", name, price, stock);
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setStock(stock);
        productService.save(product);
        return "新增成功，新商品 ID=" + product.getId();
    }

    @Tool(description = "修改商品价格。当用户要求改价、调价、修改售价时调用。")
    public String updatePrice(@ToolParam(description = "商品ID") Long id,
                              @ToolParam(description = "新价格,单位元") BigDecimal price) {
        log.info("[Tool] updatePrice id={} price={}", id, price);
        Product exists = productService.getById(id);
        if (exists == null) {
            return "未找到 ID 为 " + id + " 的商品，改价失败。";
        }
        Product update = new Product();
        update.setId(id);
        update.setPrice(price);
        productService.updateById(update);
        return "已将商品「" + exists.getName() + "」的价格从 " + exists.getPrice() + " 元改为 " + price + " 元。";
    }

    @Tool(description = "删除商品(软删除)。当用户要求删除、下架、移除某个商品时调用。")
    public String deleteProduct(@ToolParam(description = "商品ID") Long id) {
        log.info("[Tool] deleteProduct id={}", id);
        Product exists = productService.getById(id);
        if (exists == null) {
            return "未找到 ID 为 " + id + " 的商品，删除失败。";
        }
        // @TableLogic 会把它转成 UPDATE product SET is_deleted = 1 WHERE id = ?
        productService.removeById(id);
        return "已删除商品：「" + exists.getName() + "」。";
    }
}
