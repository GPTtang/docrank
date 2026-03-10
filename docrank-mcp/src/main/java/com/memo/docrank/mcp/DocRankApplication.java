package com.memo.docrank.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DocRank MCP Server 启动入口。
 *
 * <p>自动配置由 {@code docrank-spring-boot-starter} 中的
 * {@code DocRankAutoConfiguration} 提供。
 */
@SpringBootApplication(scanBasePackages = "com.memo.docrank")
public class DocRankApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocRankApplication.class, args);
    }
}
