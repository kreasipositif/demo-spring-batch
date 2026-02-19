package com.kreasipositif.accountvalidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class AccountValidationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountValidationServiceApplication.class, args);
    }
}
