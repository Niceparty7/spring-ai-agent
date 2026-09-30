package com.example.agentdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p>注意 1：@SpringBootApplication 只扫描 @Component 等注解，不会扫描接口式 Mapper，
 * 因此必须配合 @MapperScan 显式指定 Mapper 包。
 *
 * <p>注意 2：<b>必须排除 {@link RedisVectorStoreAutoConfiguration}</b>。
 * 该自动配置也注册一个名为 {@code vectorStore} 的 Bean，与 {@code RagConfig} 中
 * 手工定义的 {@code vectorStore} <b>同名冲突</b>。切勿寄望于官方 Bean 上的
 * {@code @ConditionalOnMissingBean} 会自动让位——自动配置类在启动早期即被排序注册，
 * 其条件评估可能早于用户配置类的 {@code @Bean} 方法注册，
 * 此时"missing"判定为真，自动配置照常注册 → 触发
 * "A bean with that name has already been defined ... overriding is disabled"。
 *
 * <p>为何不用 {@code spring.main.allow-bean-definition-overriding=true}：
 * 那只是让后注册者静默覆盖前者，覆盖顺序不确定；若被覆盖掉的是手工 Bean
 * （携带 metadataFields 声明），会出现"应用能启动、但按 docId 删除向量静默失效"
 * 的隐蔽故障。显式排除才是语义正确且行为确定的做法。
 */
@SpringBootApplication(exclude = RedisVectorStoreAutoConfiguration.class)
@MapperScan("com.example.agentdemo.mapper")
public class AgentCrudDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCrudDemoApplication.class, args);
    }
}