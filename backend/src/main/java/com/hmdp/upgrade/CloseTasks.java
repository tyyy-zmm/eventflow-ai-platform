package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CloseTasks {
    private final JdbcTemplate db;
    public CloseTasks(JdbcTemplate db) {this.db=db;}
    void created(String request,long user,Instant due) {
        db.update("INSERT INTO ux_close_task(request_id,user_id,due_at,next_enqueue_at) VALUES(?,?,?,CURRENT_TIMESTAMP(3))",request,user,Timestamp.from(due));
    }
    void completed(String request) {db.update("UPDATE ux_close_task SET done_at=CURRENT_TIMESTAMP(3) WHERE request_id=? AND done_at IS NULL",request);}
}
