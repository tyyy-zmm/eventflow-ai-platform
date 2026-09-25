package com.hmdp.upgrade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Accounts {
    public record User(String id,String username,String displayName) {}
    public record Session(User user,String csrfToken,Instant expiresAt) {}
    public record Login(String cookie,Session session) {}
    private record Bucket(long minute,int count) {}
    private record Stored(long id,String username,String displayName,String hash) {}
    private final JdbcTemplate db;
    private final Transactions tx;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    private final String dummyHash=passwords.encode("not-a-valid-user-password-"+random());
    private final Semaphore hashing=new Semaphore(4);
    private final ConcurrentHashMap<String,Bucket> limits=new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM=new SecureRandom();

    public Accounts(JdbcTemplate db,Transactions tx) { this.db=db;this.tx=tx; }

    static String random() {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return HexFormat.of().formatHex(bytes);
    }
    static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String username(String value) {
        String normalized=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        if(!normalized.matches("[a-z][a-z0-9_]{3,23}")) throw new Problem(400,"INVALID_USERNAME");
        return normalized;
    }
    static void password(String value) {
        if(value==null || value.length()<12 || value.getBytes(StandardCharsets.UTF_8).length>72)
            throw new Problem(400,"INVALID_PASSWORD");
    }
    private void limit(String key,int maximum) {
        long minute=System.currentTimeMillis()/60000;
        if(limits.size()>=10000) limits.entrySet().removeIf(e->e.getValue().minute()<minute);
        if(limits.size()>=10000 && !limits.containsKey(key)) throw new Problem(503,"AUTH_BUSY");
        Bucket bucket=limits.compute(key,(k,old)->new Bucket(minute,old==null||old.minute()!=minute?1:old.count()+1));
        if(bucket.count()>maximum) throw new Problem(429,"AUTH_RATE_LIMITED");
    }
    public Login register(String name,String displayName,String password,String remote) {
        limit("ip:"+remote,30);
        String user=username(name);password(password);
        String display=displayName==null?"":displayName.trim();
        if(display.isEmpty() || display.length()>24 || display.chars().anyMatch(Character::isISOControl))
            throw new Problem(400,"INVALID_DISPLAY_NAME");
        if(!hashing.tryAcquire()) throw new Problem(503,"AUTH_BUSY");
        String hash;
        try { hash=passwords.encode(password); } finally { hashing.release(); }
        try {
            return tx.run(()->{
                var keys=new GeneratedKeyHolder();
                db.update(connection->{
                    var statement=connection.prepareStatement("INSERT INTO ux_account(username,display_name,password_hash,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP(3))",new String[]{"id"});
                    statement.setString(1,user);statement.setString(2,display);statement.setString(3,hash);return statement;
                },keys);
                long id=keys.getKey().longValue();
                return createSession(new User(Long.toString(id),user,display));
            });
        } catch(DuplicateKeyException duplicate) { throw new Problem(409,"ACCOUNT_UNAVAILABLE"); }
    }
    public Login login(String name,String password,String remote) {
        limit("ip:"+remote,30);
        String normalized=username(name);password(password);limit("user:"+normalized,10);
        if(!hashing.tryAcquire()) throw new Problem(503,"AUTH_BUSY");
        Stored user;
        try {
            var rows=db.query("SELECT * FROM ux_account WHERE username=?",(r,n)->new Stored(r.getLong("id"),r.getString("username"),r.getString("display_name"),r.getString("password_hash")),normalized);
            user=rows.isEmpty()?null:rows.get(0);
            boolean matches=passwords.matches(password,user==null?dummyHash:user.hash());
            if(!matches || user==null) throw new Problem(401,"BAD_CREDENTIALS");
        } finally { hashing.release(); }
        User identity=new User(Long.toString(user.id()),user.username(),user.displayName());
        return tx.run(()->{
            // Serialize session cap decisions per account, without involving Redis.
            db.queryForObject("SELECT id FROM ux_account WHERE id=? FOR UPDATE",Long.class,user.id());
            var old=db.queryForList("SELECT token_hash FROM ux_session WHERE user_id=? ORDER BY created_at DESC,token_hash DESC",String.class,user.id());
            for(int i=7;i<old.size();i++) db.update("DELETE FROM ux_session WHERE token_hash=?",old.get(i));
            return createSession(identity);
        });
    }
    private Login createSession(User user) {
        String token=random(),csrf=random();
        Instant now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant();
        Instant expires=now.plusSeconds(28800);
        db.update("INSERT INTO ux_session(token_hash,user_id,csrf_token,created_at,expires_at) VALUES(?,?,?,?,?)",
            digest(token),Long.parseLong(user.id()),csrf,Timestamp.from(now),Timestamp.from(expires));
        return new Login(token,new Session(user,csrf,expires));
    }
    public Session authenticate(String token) {
        if(token==null || !token.matches("[a-f0-9]{64}")) throw new Problem(401,"UNAUTHORIZED");
        var rows=db.query("SELECT a.id,a.username,a.display_name,s.csrf_token,s.expires_at FROM ux_session s JOIN ux_account a ON a.id=s.user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP(3)",
            (r,n)->new Session(new User(Long.toString(r.getLong("id")),r.getString("username"),r.getString("display_name")),r.getString("csrf_token"),r.getTimestamp("expires_at").toInstant()),digest(token));
        if(rows.isEmpty()) throw new Problem(401,"UNAUTHORIZED");
        return rows.get(0);
    }
    public void logout(String token) {
        if(token!=null) db.update("DELETE FROM ux_session WHERE token_hash=?",digest(token));
    }
    @Scheduled(fixedDelay=3600000)
    public void cleanup() { db.update("DELETE FROM ux_session WHERE expires_at<CURRENT_TIMESTAMP(3) LIMIT 1000"); }
}
