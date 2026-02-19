package com.kreasipositif.batchprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class BatchProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchProcessorApplication.class, args);
    }
}
