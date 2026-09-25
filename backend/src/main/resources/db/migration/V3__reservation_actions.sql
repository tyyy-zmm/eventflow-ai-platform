CREATE TABLE ux_reservation_action (
 id VARCHAR(36) PRIMARY KEY,
 request_id VARCHAR(36) NOT NULL,
 action VARCHAR(24) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_at TIMESTAMP(3) NOT NULL,
 applied_at TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL,
 UNIQUE(request_id, action),
 FOREIGN KEY(request_id) REFERENCES ux_request(id),
 CHECK(action IN ('CONFIRM','RELEASE','RELEASE_KEEP','RESTORE'))
);
CREATE INDEX ux_reservation_action_ready ON ux_reservation_action(applied_at, next_at);
