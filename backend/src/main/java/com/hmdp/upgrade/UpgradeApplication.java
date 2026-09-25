package com.hmdp.upgrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;

@EnableScheduling
@SpringBootApplication
public class UpgradeApplication {
    public static void main(String[] args) { SpringApplication.run(UpgradeApplication.class, args); }
    @Bean NewTopic orderTopic(@Value("${upgrade.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
