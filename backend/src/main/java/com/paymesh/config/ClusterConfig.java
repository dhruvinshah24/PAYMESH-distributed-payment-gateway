package com.paymesh.config;

import com.paymesh.cluster.NodeCluster;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClusterConfig {
    @Bean(destroyMethod = "close")
    public NodeCluster nodeCluster() { return new NodeCluster(); }
}
