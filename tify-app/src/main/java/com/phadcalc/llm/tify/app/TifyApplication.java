package com.phadcalc.llm.tify.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.phadcalc.llm.tify")
@MapperScan(value = "com.phadcalc.llm.tify.**.mapper", lazyInitialization = "${mybatis.lazy-initialization:false}")
@EnableScheduling
public class TifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(TifyApplication.class, args);
    }
}
