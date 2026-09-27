package com.example.agentdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 * 注意：@SpringBootApplication 只扫描 @Component 等注解，不会扫描接口式 Mapper，
 * 因此必须配合 @MapperScan 显式指定 Mapper 包。
 */
@SpringBootApplication
@MapperScan("com.example.agentdemo.mapper")
public class AgentCrudDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCrudDemoApplication.class, args);
    }
}