package com.example.agentdemo.config;

import com.example.agentdemo.agent.KnowledgeTools;
import com.example.agentdemo.agent.ProductTools;
import com.example.agentdemo.skill.SkillDependencyResolver;
import com.example.agentdemo.skill.SkillInstructionsAssembler;
import com.example.agentdemo.skill.SkillInstructionAdvisor;
import com.example.agentdemo.skill.SkillParser;
import com.example.agentdemo.skill.SkillProperties;
import com.example.agentdemo.skill.SkillRegistry;
import com.example.agentdemo.skill.SkillScanner;
import com.example.agentdemo.skill.SkillScriptRunner;
import com.example.agentdemo.skill.SkillSessionState;
import com.example.agentdemo.skill.SkillToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * skill 系统装配：扫描/注册/会话态/工具索引/指令注入/脚本执行等全部 Bean。
 *
 * <p>与 {@code RagConfig} 同范式：{@code app.skill.*} 配置 + 手工装配 + 降级容错。
 * 工具索引（{@link SkillToolRegistry}）复用 {@code MethodToolCallbackProvider}，
 * 与 {@code McpServerToolsConfig} 同款，不发明反射。
 *
 * <p>启动扫描与热重载的生命周期逻辑见 {@code SkillLifecycle}（独立 {@code @Component}，
 * 便于构造注入，不把生命周期方法混进配置类）。
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfig {

    @Bean
    public SkillParser skillParser() {
        return new SkillParser();
    }

    @Bean
    public SkillScanner skillScanner(SkillParser skillParser) {
        return new SkillScanner(skillParser);
    }

    @Bean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    public SkillSessionState skillSessionState(SkillProperties properties) {
        return new SkillSessionState(properties.getSessionTtl());
    }

    @Bean
    public SkillToolRegistry skillToolRegistry(ProductTools productTools, KnowledgeTools knowledgeTools) {
        SkillToolRegistry registry = new SkillToolRegistry();
        registry.index(MethodToolCallbackProvider.builder()
                .toolObjects(productTools, knowledgeTools)
                .build()
                .getToolCallbacks());
        log.info("skill 工具索引已建立，可引用工具: {}", registry.names());
        return registry;
    }

    @Bean
    public SkillInstructionsAssembler skillInstructionsAssembler(SkillProperties properties,
                                                                 SkillRegistry registry,
                                                                 SkillSessionState sessionState) {
        return new SkillInstructionsAssembler(properties, registry, sessionState);
    }

    @Bean
    public SkillScriptRunner skillScriptRunner(SkillProperties properties) {
        return new SkillScriptRunner(properties);
    }

    @Bean
    public SkillDependencyResolver skillDependencyResolver() {
        return new SkillDependencyResolver();
    }

    @Bean
    public SkillInstructionAdvisor skillInstructionAdvisor(SkillInstructionsAssembler assembler) {
        return new SkillInstructionAdvisor(assembler);
    }
}
