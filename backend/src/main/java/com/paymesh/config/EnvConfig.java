package com.paymesh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnvConfig {
    @Value("${paymesh.node-id:NODE-1}")
    public String nodeId;
}
