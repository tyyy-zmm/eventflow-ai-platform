package com.hmdp.upgrade;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class CacheNotifications {
    static final class Listener implements MessageListener,SubscriptionListener {
        private final ShopCache cache;
        Listener(ShopCache cache) { this.cache=cache; }
        @Override public void onMessage(Message message,byte[] pattern) {
            cache.invalidateLocal(new String(message.getBody(),StandardCharsets.UTF_8));
        }
        @Override public void onChannelSubscribed(byte[] channel,long count) { cache.clearLocal(); }
        @Override public void onChannelUnsubscribed(byte[] channel,long count) { cache.clearLocal(); }
    }
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor cacheNotificationExecutor() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("cache-invalidation-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
    @Bean("shopInvalidationListener") RedisMessageListenerContainer cacheNotifications(RedisConnectionFactory factory,ShopCache cache,
        @org.springframework.beans.factory.annotation.Qualifier("cacheNotificationExecutor") java.util.concurrent.Executor executor) {
        var container=new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(new Listener(cache),new ChannelTopic(ShopCache.INVALIDATION_CHANNEL));
        container.setTaskExecutor(executor);
        return container;
    }
}
