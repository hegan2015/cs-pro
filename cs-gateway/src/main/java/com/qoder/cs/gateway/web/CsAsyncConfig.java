package com.qoder.cs.gateway.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * LLM 请求线程隔离：消息应答走 MVC 异步 + 专用有界线程池，
 * 慢 LLM 调用不占用 Tomcat 请求线程（健康检查/SSE/页面不受拖累）；池满快速拒绝 503。
 */
@Configuration
public class CsAsyncConfig implements WebMvcConfigurer {

    @org.springframework.beans.factory.annotation.Value("${cs.uploads.dir:./data/uploads}")
    private String uploadsDir;

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + java.nio.file.Path.of(uploadsDir).toAbsolutePath().normalize() + "/");
    }

    @Bean
    public ThreadPoolTaskExecutor llmRequestExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("cs-llm-req-");
        ex.setDaemon(true);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(llmRequestExecutor());
        configurer.setDefaultTimeout(120_000);
    }
}