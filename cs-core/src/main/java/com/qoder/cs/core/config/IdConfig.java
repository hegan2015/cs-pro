package com.qoder.cs.core.config;

import com.qoder.cs.common.id.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 取号器按 cs.db.mode 选方言：h2 用原生 SEQUENCE，mysql 用号表原子取号。 */
@Configuration
public class IdConfig {

    @Bean
    public IdGenerator idGenerator(DataSource ds, @Value("${cs.db.mode:h2}") String dbMode) {
        return new IdGenerator(ds, "mysql".equalsIgnoreCase(dbMode));
    }
}
