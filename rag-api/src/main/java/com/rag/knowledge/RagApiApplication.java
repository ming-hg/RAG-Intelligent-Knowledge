package com.rag.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApiApplication.class, args);
    }
}
