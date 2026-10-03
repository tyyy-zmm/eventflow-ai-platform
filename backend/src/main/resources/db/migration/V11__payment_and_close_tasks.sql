CREATE TABLE ux_close_task (
 request_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL,
 due_at TIMESTAMP(3) NOT NULL, next_enqueue_at TIMESTAMP(3) NOT NULL,
 done_at TIMESTAMP(3), FOREIGN KEY(request_id) REFERENCES ux_request(id)
);
CREATE INDEX ux_close_ready ON ux_close_task(done_at,next_enqueue_at);
CREATE INDEX ux_close_due ON ux_close_task(done_at,due_at);
INSERT INTO ux_close_task(request_id,user_id,due_at,next_enqueue_at)
 SELECT request_id,user_id,confirm_until,CURRENT_TIMESTAMP(3) FROM ux_order WHERE state='PENDING_CONFIRM';
CREATE TABLE ux_payment (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, amount_cents INT NOT NULL,
 state VARCHAR(24) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(request_id) REFERENCES ux_request(id)
);
CREATE TABLE ux_payment_receipt (
 channel_id VARCHAR(64) PRIMARY KEY, payment_id VARCHAR(36) NOT NULL,
 amount_cents INT NOT NULL, outcome VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(payment_id) REFERENCES ux_payment(id)
);
CREATE TABLE ux_refund (
 channel_id VARCHAR(64) PRIMARY KEY, payment_id VARCHAR(36) NOT NULL,
 amount_cents INT NOT NULL, attempts INT NOT NULL DEFAULT 0,
 next_at TIMESTAMP(3) NOT NULL, done_at TIMESTAMP(3),
 FOREIGN KEY(channel_id) REFERENCES ux_payment_receipt(channel_id)
);
-- Durable simulated provider ledger. No real funds are charged or refunded.
CREATE TABLE ux_sandbox_refund (
 channel_id VARCHAR(64) PRIMARY KEY, amount_cents INT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL
);
