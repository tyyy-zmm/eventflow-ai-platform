package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Reservations {
    enum Decision { ADMITTED, REPLAY_PENDING, REPLAY_CONFIRMED, REPLAY_RELEASED, REPLAY_RESTORED, ALREADY_PURCHASED, SOLD_OUT }
    record Result(Decision decision, boolean newReservation) {}

    private static final String ACTIVITIES="ux:reserve:activities";

    static final DefaultRedisScript<Long> INITIALIZE=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1]) == 0 then redis.call('SET',KEYS[1],ARGV[1]) end
        redis.call('SADD',KEYS[5],ARGV[2])
        return tonumber(redis.call('GET',KEYS[1]))
        """,Long.class);

    static final DefaultRedisScript<Long> RESERVE=new DefaultRedisScript<>("""
        local field=ARGV[2] .. ':' .. ARGV[3]
        local replay=redis.call('HGET',KEYS[3],field)
        if replay then return 10 + tonumber(replay) end
        if redis.call('HEXISTS',KEYS[2],ARGV[2]) == 1 then return 2 end
        local stock=tonumber(redis.call('GET',KEYS[1]) or '-1')
        if stock <= 0 then return 3 end
        redis.call('DECR',KEYS[1])
        redis.call('HSET',KEYS[2],ARGV[2],ARGV[3])
        redis.call('HSET',KEYS[3],field,'1')
        redis.call('ZADD',KEYS[4],ARGV[4],field)
        redis.call('SADD',KEYS[5],ARGV[1])
        return 1
        """,Long.class);

    static final DefaultRedisScript<Long> APPLY=new DefaultRedisScript<>("""
        local field=ARGV[1] .. ':' .. ARGV[2]
        local state=tonumber(redis.call('HGET',KEYS[3],field) or '0')
        local action=ARGV[3]
        if action == 'CONFIRM' then
          if state == 1 then redis.call('HSET',KEYS[3],field,'2'); redis.call('ZREM',KEYS[4],field); return 1 end
          if state == 2 or state == 4 then return 0 end
          return -1
        end
        if action == 'RELEASE' or action == 'RELEASE_KEEP' then
          if state == 1 then
            redis.call('INCR',KEYS[1]); redis.call('HSET',KEYS[3],field,'3'); redis.call('ZREM',KEYS[4],field)
            if action == 'RELEASE' and redis.call('HGET',KEYS[2],ARGV[1]) == ARGV[2] then redis.call('HDEL',KEYS[2],ARGV[1]) end
            return 1
          end
          if state == 3 or state == 4 then return 0 end
          return -1
        end
        if action == 'RESTORE' then
          if state == 1 or state == 2 then
            redis.call('INCR',KEYS[1]); redis.call('HSET',KEYS[3],field,'4'); redis.call('ZREM',KEYS[4],field); return 1
          end
          if state == 4 then return 0 end
          return -1
        end
        return -2
        """,Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate db;

    Reservations(StringRedisTemplate redis,JdbcTemplate db) { this.redis=redis;this.db=db; }

    void prepare(long activity,int available) {
        if(activity<=0 || available<0) throw new IllegalArgumentException();
        Long initialized=redis.execute(INITIALIZE,keys(activity),Integer.toString(available),Long.toString(activity));
        if(initialized==null) throw new Problem(503,"RESERVATION_UNAVAILABLE");
    }

    Result reserve(long activity,long user,String requestKey,Instant deadline) {
        Trading.validate(requestKey,activity);
        ensure(activity);
        Long code=redis.execute(RESERVE,keys(activity),Long.toString(activity),Long.toString(user),requestKey,
            Long.toString(deadline.toEpochMilli()));
        if(code==null) throw new Problem(503,"RESERVATION_UNAVAILABLE");
        return switch(code.intValue()) {
            case 1 -> new Result(Decision.ADMITTED,true);
            case 2 -> new Result(Decision.ALREADY_PURCHASED,false);
            case 3 -> new Result(Decision.SOLD_OUT,false);
            case 11 -> new Result(Decision.REPLAY_PENDING,false);
            case 12 -> new Result(Decision.REPLAY_CONFIRMED,false);
            case 13 -> new Result(Decision.REPLAY_RELEASED,false);
            case 14 -> new Result(Decision.REPLAY_RESTORED,false);
            default -> throw new Problem(503,"RESERVATION_UNAVAILABLE");
        };
    }

    private void ensure(long activity) {
        if(Boolean.TRUE.equals(redis.hasKey(keys(activity).get(0)))) return;
        var rows=db.queryForList("SELECT available FROM ux_activity WHERE id=?",activity);
        if(rows.isEmpty()) throw new Problem(404,"ACTIVITY_NOT_FOUND");
        prepare(activity,((Number)rows.get(0).get("available")).intValue());
    }

    void releaseNow(long activity,long user,String requestKey,boolean keepUser) {
        apply(activity,user,requestKey,keepUser?"RELEASE_KEEP":"RELEASE");
    }

    private void apply(long activity,long user,String requestKey,String action) {
        Long result=redis.execute(APPLY,keys(activity),Long.toString(user),requestKey,action);
        if(result==null || result<=-2) throw new Problem(503,"RESERVATION_UNAVAILABLE");
    }

    int drainActions() {
        int applied=0;
        var rows=db.queryForList("""
            SELECT a.id,a.action,a.attempts,r.id request_id,r.user_id,r.request_key,r.activity_id
            FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id
            WHERE a.applied_at IS NULL AND a.next_at<=CURRENT_TIMESTAMP(3)
            ORDER BY a.created_at,a.id LIMIT 100
            """);
        for(var row:rows) {
            String id=(String)row.get("id");
            try {
                apply(((Number)row.get("activity_id")).longValue(),((Number)row.get("user_id")).longValue(),
                    (String)row.get("request_key"),(String)row.get("action"));
                applied+=db.update("UPDATE ux_reservation_action SET applied_at=CURRENT_TIMESTAMP(3),attempts=attempts+1 WHERE id=? AND applied_at IS NULL",id);
            } catch(RuntimeException unavailable) {
                int attempts=((Number)row.getOrDefault("attempts",0)).intValue()+1;
                long delay=Math.min(30,1L<<Math.min(5,attempts));
                db.update("UPDATE ux_reservation_action SET attempts=attempts+1,next_at=? WHERE id=? AND applied_at IS NULL",
                    Timestamp.from(Instant.now().plusSeconds(delay)),id);
                break;
            }
        }
        return applied;
    }

    int reconcileStale() {
        Set<String> activities=redis.opsForSet().members(ACTIVITIES);
        if(activities==null || activities.isEmpty()) return 0;
        int repaired=0;double cutoff=Instant.now().toEpochMilli();
        for(String value:activities) {
            long activity;
            try { activity=Long.parseLong(value); } catch(NumberFormatException bad) { continue; }
            Set<String> pending=redis.opsForZSet().rangeByScore(keys(activity).get(3),0,cutoff,0,100);
            if(pending==null) continue;
            for(String field:pending) repaired+=reconcile(activity,field);
        }
        return repaired;
    }

    private int reconcile(long activity,String field) {
        int split=field.indexOf(':');if(split<=0) return 0;
        long user;try { user=Long.parseLong(field.substring(0,split)); } catch(NumberFormatException bad) { return 0; }
        String requestKey=field.substring(split+1);
        var rows=db.queryForList("SELECT id,state,reason,deadline FROM ux_request WHERE user_id=? AND request_key=? AND activity_id=?",user,requestKey,activity);
        if(rows.isEmpty()) { apply(activity,user,requestKey,"RELEASE");return 1; }
        var row=rows.get(0);String state=(String)row.get("state");
        if("ACCEPTED".equals(state)) {
            redis.opsForZSet().add(keys(activity).get(3),field,((Timestamp)row.get("deadline")).toInstant().toEpochMilli());return 0;
        }
        String action="SUCCEEDED".equals(state)?"CONFIRM":"ALREADY_PURCHASED".equals(row.get("reason"))?"RELEASE_KEEP":"RELEASE";
        apply(activity,user,requestKey,action);return 1;
    }

    Map<String,Object> metrics() {
        Long pending=0L;Set<String> activities=redis.opsForSet().members(ACTIVITIES);
        if(activities!=null) for(String activity:activities) {
            Long count=redis.opsForZSet().zCard("ux:reserve:{"+activity+"}:pending");if(count!=null) pending+=count;
        }
        Long actions=db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE applied_at IS NULL",Long.class);
        return Map.of("redisPending",pending,"unappliedActions",actions==null?0:actions);
    }

    int remaining(long activity) {
        String value=redis.opsForValue().get(keys(activity).get(0));
        return value==null?-1:Integer.parseInt(value);
    }

    long pending(long activity) {
        Long value=redis.opsForZSet().zCard(keys(activity).get(3));
        return value==null?0:value;
    }

    String state(long activity,long user,String requestKey) {
        return (String)redis.opsForHash().get(keys(activity).get(2),user+":"+requestKey);
    }

    private static List<String> keys(long activity) {
        String base="ux:reserve:{"+activity+"}";
        return List.of(base+":stock",base+":users",base+":requests",base+":pending",ACTIVITIES);
    }
}
