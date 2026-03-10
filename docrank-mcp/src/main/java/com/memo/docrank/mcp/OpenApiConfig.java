package com.memo.docrank.mcp;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI docRankOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DocRank API")
                        .description("JVM 原生离线多语言 RAG 框架 — 混合检索（BM25 + Vector + Reranker）+ AI Agent 问答")
                        .version("1.0.0")
                        .contact(new Contact().name("DocRank").url("https://github.com/GPTtang/docrank"))
                        .license(new License().name("Apache 2.0")))
                .tags(List.of(
                        new Tag().name("知识库").description("文档写入、搜索、删除"),
                        new Tag().name("Agent").description("RAG 问答 + 会话管理")));
    }
}
