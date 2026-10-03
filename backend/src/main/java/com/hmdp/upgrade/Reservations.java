package com.hmdp.upgrade;

import static com.hmdp.upgrade.PipelineMetrics.Stage.*;

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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PipelineMetrics telemetry=new PipelineMetrics();
    enum Decision { ADMITTED, REPLAY_PENDING, REPLAY_CONFIRMED, REPLAY_RELEASED, REPLAY_RESTORED, ALREADY_PURCHASED, SOLD_OUT }
    record Result(Decision decision, boolean newReservation,String epoch) {}

    static final DefaultRedisScript<String> RESERVE=new DefaultRedisScript<>("""
        local epoch=redis.call('GET',KEYS[5])
        if not epoch or string.sub(epoch,1,1)=='!' or redis.call('EXISTS',KEYS[1])==0 then return '-1:' end
        local field=ARGV[2] .. ':' .. ARGV[3]
        local replay=redis.call('HGET',KEYS[3],field)
        if replay then return tostring(10 + tonumber(replay)) .. ':' .. epoch end
        if redis.call('HEXISTS',KEYS[2],ARGV[2]) == 1 then return '2:' .. epoch end
        local stock=tonumber(redis.call('GET',KEYS[1]) or '-1')
        if stock <= 0 then return '3:' .. epoch end
        redis.call('DECR',KEYS[1])
        redis.call('HSET',KEYS[2],ARGV[2],ARGV[3])
        redis.call('HSET',KEYS[3],field,'1')
        redis.call('ZADD',KEYS[4],ARGV[4],field)
        return '1:' .. epoch
        """,String.class);
    static final DefaultRedisScript<Long> REBUILD=new DefaultRedisScript<>("""
        redis.call('SET',KEYS[5],'!' .. ARGV[1])
        redis.call('DEL',KEYS[1],KEYS[2],KEYS[3],KEYS[4])
        redis.call('SET',KEYS[1],ARGV[2])
        local n=tonumber(ARGV[3]);local i=4
        for j=1,n do redis.call('HSET',KEYS[3],ARGV[i],ARGV[i+1]);i=i+2 end
        while i<=#ARGV do redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]);i=i+2 end
        return 1
        """,Long.class);
    static final DefaultRedisScript<Long> ACTIVATE=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])~='!' .. ARGV[1] then return 0 end
        redis.call('SET',KEYS[1],ARGV[1]);return 1
        """,Long.class);

    static final DefaultRedisScript<Long> APPLY=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1]) == 0 then return -1 end
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

    @org.springframework.beans.factory.annotation.Value("${upgrade.repair-batch:100}")
    private int repairBatch=100;
    @org.springframework.beans.factory.annotation.Value("${upgrade.reconcile-page-size:32}")
    private int reconcilePageSize=32;
    @org.springframework.beans.factory.annotation.Value("${upgrade.reconcile-budget:100}")
    private int reconcileBudget=100;
    private long reconcileCursor;
    private final java.util.concurrent.atomic.LongAdder scannedActivities=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder completedSweeps=new java.util.concurrent.atomic.LongAdder();
    @jakarta.annotation.PostConstruct void validateSettings() {
        if(repairBatch<1 || repairBatch>1000 || reconcilePageSize<1 || reconcilePageSize>256 || reconcileBudget<1 || reconcileBudget>1000) throw new IllegalArgumentException("repair batch must be 1..1000");
    }
    Map<String,Object> settings() { return Map.of("batch",repairBatch,"reconcilePageSize",reconcilePageSize,"reconcileBudget",reconcileBudget,"scannedActivities",scannedActivities.sum(),"completedSweeps",completedSweeps.sum()); }
    private final Trading trading;
    private final Transactions tx;
    Reservations(StringRedisTemplate redis,JdbcTemplate db,Trading trading,Transactions tx) {
        this.redis=redis;this.db=db;this.trading=trading;this.tx=tx;
    }

    void prepare(long activity,int available) {
        if(activity<=0 || available<0) throw new IllegalArgumentException();
        rebuild(activity,0,true);
    }
    String recover(long activity,long actor) { return rebuild(activity,actor,false); }
    private String rebuild(long activity,long actor,boolean initial) {
        String epoch=tx.run(()->{
            var activities=db.queryForList("SELECT available FROM ux_activity WHERE id=? FOR UPDATE",activity);
            if(activities.isEmpty()) throw new Problem(404,"ACTIVITY_NOT_FOUND");
            var previous=db.queryForList("SELECT epoch FROM ux_reservation_epoch WHERE activity_id=?",activity);
            if(initial && !previous.isEmpty()) return null;
            long count=db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE activity_id=?",Long.class,activity);
            if(initial && count!=0) throw new Problem(409,"RESERVATION_RECOVERY_REQUIRED");
            // This administrative operation deliberately holds the activity lock while
            // installing a bounded snapshot. Normal request transactions do not call Redis.
            if(count>10_000) throw new Problem(409,"RECOVERY_BATCH_REQUIRED");
            trading.expireForRecovery(activity);
            String next=Trading.uuid();
            if(previous.isEmpty()) db.update("INSERT INTO ux_reservation_epoch(activity_id,epoch) VALUES(?,?)",activity,next);
            else db.update("UPDATE ux_reservation_epoch SET epoch=? WHERE activity_id=?",next,activity);
            var requests=db.queryForList("SELECT r.user_id,r.request_key,r.state,o.state order_state FROM ux_request r LEFT JOIN ux_order o ON o.request_id=r.id WHERE r.activity_id=?",activity);
            var args=new java.util.ArrayList<String>();
            args.add(next);args.add(activities.get(0).get("available").toString());args.add(Integer.toString(requests.size()));
            for(var row:requests) {
                args.add(row.get("user_id")+":"+row.get("request_key"));
                Object order=row.get("order_state");
                args.add(order==null?"3":List.of("CANCELLED","EXPIRED").contains(order)?"4":"2");
            }
            for(var row:requests) if(row.get("order_state")!=null) {
                args.add(row.get("user_id").toString());args.add(row.get("request_key").toString());
            }
            if(!Long.valueOf(1).equals(redis.execute(REBUILD,keys(activity),args.toArray())))
                throw new Problem(503,"RESERVATION_UNAVAILABLE");
            db.update("INSERT INTO ux_audit(id,actor,action,target,created_at) VALUES(?,?,'RESERVATION_RECOVERY',?,CURRENT_TIMESTAMP(3))",Trading.uuid(),actor,Long.toString(activity));
            return next;
        });
        if(epoch==null) return null;
        // Only activate after a known successful DB commit. An unknown commit leaves
        // Redis paused; repeating recovery is safe and creates a fresh epoch.
        if(!Long.valueOf(1).equals(redis.execute(ACTIVATE,List.of(keys(activity).get(4)),epoch)))
            throw new Problem(503,"RESERVATION_RECOVERY_REQUIRED");
        return epoch;
    }

    Result reserve(long activity,long user,String requestKey,Instant deadline) {
        Trading.validate(requestKey,activity);
        ensure(activity);
        String reply=redis.execute(RESERVE,keys(activity),Long.toString(activity),Long.toString(user),requestKey,
            Long.toString(deadline.toEpochMilli()));
        if(reply==null) throw new Problem(503,"RESERVATION_UNAVAILABLE");
        String[] parts=reply.split(":",2);String epoch=parts[1];
        return switch(Integer.parseInt(parts[0])) {
            case 1 -> new Result(Decision.ADMITTED,true,epoch);
            case 2 -> new Result(Decision.ALREADY_PURCHASED,false,epoch);
            case 3 -> new Result(Decision.SOLD_OUT,false,epoch);
            case 11 -> new Result(Decision.REPLAY_PENDING,false,epoch);
            case 12 -> new Result(Decision.REPLAY_CONFIRMED,false,epoch);
            case 13 -> new Result(Decision.REPLAY_RELEASED,false,epoch);
            case 14 -> new Result(Decision.REPLAY_RESTORED,false,epoch);
            default -> throw new Problem(503,"RESERVATION_UNAVAILABLE");
        };
    }

    private void ensure(long activity) {
        if(Boolean.TRUE.equals(redis.hasKey(keys(activity).get(0)))) return;
        if(db.queryForObject("SELECT COUNT(*) FROM ux_activity WHERE id=?",Integer.class,activity)==0)
            throw new Problem(404,"ACTIVITY_NOT_FOUND");
        // Missing Redis state is not proof that no in-flight reservations exist.
        throw new Problem(503,"RESERVATION_RECOVERY_REQUIRED");
    }

    void releaseNow(long activity,long user,String requestKey,String epoch) {
        reconcile(activity,user+":"+requestKey,epoch);
    }

    private void apply(long activity,long user,String requestKey,String action) {
        telemetry.measure(RESERVATION_APPLY,()->{
            Long result=redis.execute(APPLY,keys(activity),Long.toString(user),requestKey,action);
            if(result==null || result<0) throw new Problem(503,"RESERVATION_UNAVAILABLE");
            return result;
        });
    }

    // Ready-time order matches (applied_at,next_at) plus the implicit primary key,
    // avoiding a full scan/filesort as completed history grows.
    int drainActions() {
        int applied=0;
        var rows=db.queryForList("""
            SELECT a.id,a.action,a.attempts,a.created_at,r.id request_id,r.user_id,r.request_key,r.activity_id
            FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id
            JOIN ux_reservation_epoch e ON e.activity_id=r.activity_id
            WHERE a.applied_at IS NULL AND a.next_at<=CURRENT_TIMESTAMP(3)
            ORDER BY a.next_at,a.id LIMIT ?
            """,repairBatch);
        for(var row:rows) {
            String id=(String)row.get("id");
            try {
                telemetry.age(RESERVATION_AGE,((Timestamp)row.get("created_at")).getTime());
                apply(((Number)row.get("activity_id")).longValue(),((Number)row.get("user_id")).longValue(),
                    (String)row.get("request_key"),(String)row.get("action"));
                applied+=db.update("UPDATE ux_reservation_action SET applied_at=CURRENT_TIMESTAMP(3),attempts=attempts+1 WHERE id=? AND applied_at IS NULL",id);
            } catch(RuntimeException unavailable) {
                int attempts=((Number)row.getOrDefault("attempts",0)).intValue()+1;
                long delay=Math.min(30,1L<<Math.min(5,attempts));
                db.update("UPDATE ux_reservation_action SET attempts=attempts+1,next_at=? WHERE id=? AND applied_at IS NULL",
                    Timestamp.from(Instant.now().plusSeconds(delay)),id);
                continue;
            }
        }
        return applied;
    }

    private record Visit(int inspected,int repaired) {}
    synchronized int reconcileStale() {
        var activities=db.queryForList("SELECT activity_id FROM ux_reservation_epoch WHERE activity_id>? ORDER BY activity_id LIMIT ?",Long.class,reconcileCursor,reconcilePageSize);
        if(activities.isEmpty()) {reconcileCursor=0;completedSweeps.increment();return 0;}
        int repaired=0,remaining=reconcileBudget;double cutoff=Instant.now().toEpochMilli();
        for(long activity:activities) {
            // Advance only after this activity has actually been visited; a failed Redis
            // call is revisited on the next sweep, rather than starving every later activity.
            reconcileCursor=activity;scannedActivities.increment();
            final int limit=Math.min(100,remaining);
            Visit visit=telemetry.measure(RECONCILE_ACTIVITY,()->{
                String epoch=redis.opsForValue().get(keys(activity).get(4));
                if(epoch==null || epoch.startsWith("!")) return new Visit(0,0);
                Set<String> fields=redis.opsForZSet().rangeByScore(keys(activity).get(3),0,cutoff,0,limit);
                if(fields==null) return new Visit(0,0);
                // Keep the observed epoch paired with this visit; recovery can fence it.
                int changed=0;for(String field:fields) changed+=reconcile(activity,field,epoch);
                return new Visit(fields.size(),changed);
            });
            repaired+=visit.repaired();remaining-=visit.inspected();
            if(remaining==0) break;
        }
        return repaired;
    }

    private int reconcile(long activity,String field,String epoch) {
        int split=field.indexOf(':');if(split<=0) return 0;
        long user;try { user=Long.parseLong(field.substring(0,split)); } catch(NumberFormatException bad) { return 0; }
        String requestKey=field.substring(split+1);
        var request=trading.adjudicateReservation(user,requestKey,activity,epoch);
        if(request==null) return 0;
        if(request.activityId()!=activity) { apply(activity,user,requestKey,"RELEASE");return 1; }
        if("ACCEPTED".equals(request.state())) {
            redis.opsForZSet().add(keys(activity).get(3),field,request.deadline().toEpochMilli());return 0;
        }
        String action;
        if("SUCCEEDED".equals(request.state())) {
            String orderState=(String)trading.order(user,request.id()).get("state");
            action=List.of("CANCELLED","EXPIRED").contains(orderState)?"RESTORE":"CONFIRM";
        } else action="ALREADY_PURCHASED".equals(request.reason())?"RELEASE_KEEP":"RELEASE";
        apply(activity,user,requestKey,action);return 1;
    }

    Map<String,Object> metrics() {
        Long pending=0L;var activities=db.queryForList("SELECT activity_id FROM ux_reservation_epoch",Long.class);
        for(long activity:activities) {
            Long count=redis.opsForZSet().zCard("ux:reserve:{"+activity+"}:pending");if(count!=null) pending+=count;
        }
        Long actions=db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id JOIN ux_reservation_epoch e ON e.activity_id=r.activity_id WHERE a.applied_at IS NULL",Long.class);
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
        return List.of(base+":stock",base+":users",base+":requests",base+":pending",base+":epoch");
    }
}
