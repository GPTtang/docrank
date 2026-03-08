package com.memo.docrank.webui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration for the DocRank Web Admin UI.
 *
 * <p>Serves the admin UI at <code>/docrank-ui/</code>.
 * Add this module to your classpath to enable the UI:
 *
 * <pre>{@code
 * <dependency>
 *   <groupId>com.memo</groupId>
 *   <artifactId>docrank-webui</artifactId>
 * </dependency>
 * }</pre>
 *
 * Access the UI at: <a href="http://localhost:8080/docrank-ui/">http://localhost:8080/docrank-ui/</a>
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication
public class DocRankWebUiAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docrank-ui/**")
                .addResourceLocations("classpath:/static/docrank-ui/");
        log.info("DocRank Web UI 已启用: /docrank-ui/");
    }

    @Bean
    public DocRankWebUiController docRankWebUiController() {
        return new DocRankWebUiController();
    }
}
