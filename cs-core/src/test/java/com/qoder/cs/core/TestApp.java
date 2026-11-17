package com.qoder.cs.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.qoder.cs")
@MapperScan({"com.qoder.cs.kb", "com.qoder.cs.core.chat", "com.qoder.cs.core.ticket",
        "com.qoder.cs.core.stats", "com.qoder.cs.core.support", "com.qoder.cs.core.subscription"})
public class TestApp {
}