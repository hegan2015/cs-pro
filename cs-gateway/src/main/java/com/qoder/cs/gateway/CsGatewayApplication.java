package com.qoder.cs.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.qoder.cs")
@MapperScan({"com.qoder.cs.kb", "com.qoder.cs.core.chat", "com.qoder.cs.core.ticket",
        "com.qoder.cs.core.stats", "com.qoder.cs.core.support", "com.qoder.cs.core.subscription",
        "com.qoder.cs.core.proactive", "com.qoder.cs.core.open", "com.qoder.cs.core.billing",
        "com.qoder.cs.core.audit"})
public class CsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsGatewayApplication.class, args);
    }
}