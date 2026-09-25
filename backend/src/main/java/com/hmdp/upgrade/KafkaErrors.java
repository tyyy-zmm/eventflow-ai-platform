package com.hmdp.upgrade;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrors {
    @Bean DefaultErrorHandler errorHandler() {
        // Infrastructure failures must never be silently skipped after a finite retry count.
        return new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
