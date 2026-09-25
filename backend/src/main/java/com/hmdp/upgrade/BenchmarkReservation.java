package com.hmdp.upgrade;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-first admission used only by the controlled benchmark endpoints. */
@Component
public class BenchmarkReservation {
    enum Decision { ADMITTED, ALREADY_PURCHASED, SOLD_OUT }

    static final DefaultRedisScript<Long> PREPARE = new DefaultRedisScript<>("""
        redis.call('SET', KEYS[1], ARGV[1])
        redis.call('DEL', KEYS[2], KEYS[3])
        return 1
        """, Long.class);

    static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        local requestField = ARGV[1] .. ':' .. ARGV[2]
        local replay = redis.call('HGET', KEYS[3], requestField)
        if replay then return tonumber(replay) end
        if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then
          redis.call('HSET', KEYS[3], requestField, '2')
          return 2
        end
        local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
        if stock <= 0 then
          redis.call('HSET', KEYS[3], requestField, '3')
          return 3
        end
        redis.call('DECR', KEYS[1])
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
        redis.call('HSET', KEYS[3], requestField, '1')
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;

    BenchmarkReservation(StringRedisTemplate redis) { this.redis = redis; }

    void prepare(long activity, int stock) {
        if(stock < 0) throw new IllegalArgumentException("stock must be non-negative");
        redis.execute(PREPARE, keys(activity), Integer.toString(stock));
    }

    Decision reserve(long activity, long user, String requestKey) {
        Trading.validate(requestKey, activity);
        Long result = redis.execute(RESERVE, keys(activity), Long.toString(user), requestKey);
        if(result == null || result < 1 || result > 3) throw new Problem(503, "RESERVATION_UNAVAILABLE");
        return Decision.values()[result.intValue() - 1];
    }

    int remaining(long activity) {
        String value = redis.opsForValue().get(keys(activity).get(0));
        return value == null ? -1 : Integer.parseInt(value);
    }

    private static List<String> keys(long activity) {
        String base = "ux:bench:{" + activity + "}";
        return List.of(base + ":stock", base + ":users", base + ":requests");
    }
}
