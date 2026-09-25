CREATE TABLE ux_activity (
 id BIGINT PRIMARY KEY, capacity INT NOT NULL, available INT NOT NULL,
 price_cents INT NOT NULL, starts_at TIMESTAMP(3) NOT NULL, ends_at TIMESTAMP(3) NOT NULL,
 process_until TIMESTAMP(3) NOT NULL,
 CHECK (available >= 0 AND available <= capacity), CHECK (price_cents >= 0)
);
CREATE TABLE ux_request (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL,
 activity_id BIGINT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, reason VARCHAR(40), created_at TIMESTAMP(3) NOT NULL,
 deadline TIMESTAMP(3) NOT NULL, completed_at TIMESTAMP(3),
 UNIQUE (user_id, request_key), FOREIGN KEY (activity_id) REFERENCES ux_activity(id)
);
CREATE INDEX ux_request_pending ON ux_request(state, created_at);
CREATE INDEX ux_request_expiry ON ux_request(state, deadline);
CREATE TABLE ux_order (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE,
 activity_id BIGINT NOT NULL, user_id BIGINT NOT NULL, price_cents INT NOT NULL,
 state VARCHAR(24) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 UNIQUE (activity_id, user_id), FOREIGN KEY (request_id) REFERENCES ux_request(id),
 FOREIGN KEY (activity_id) REFERENCES ux_activity(id)
);
CREATE INDEX ux_order_expiry ON ux_order(state, confirm_until);
CREATE TABLE ux_outbox (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE,
 activity_id BIGINT NOT NULL, owner VARCHAR(36), lease_until TIMESTAMP(3),
 attempts INT NOT NULL DEFAULT 0, next_at TIMESTAMP(3) NOT NULL,
 sent_at TIMESTAMP(3), created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY (request_id) REFERENCES ux_request(id)
);
CREATE INDEX ux_outbox_ready ON ux_outbox(sent_at, next_at, lease_until);
CREATE TABLE ux_poison (
 id VARCHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL, partition_id INT NOT NULL,
 offset_id BIGINT NOT NULL, body TEXT NOT NULL, reason VARCHAR(128) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, UNIQUE(topic, partition_id, offset_id)
);
CREATE TABLE ux_audit (
 id VARCHAR(36) PRIMARY KEY, actor BIGINT NOT NULL, action VARCHAR(32) NOT NULL,
 target VARCHAR(64) NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE ux_shop (
 id BIGINT PRIMARY KEY, name VARCHAR(200) NOT NULL, description TEXT NOT NULL,
 revision BIGINT NOT NULL
);
CREATE TABLE ux_invalidation (
 cache_key VARCHAR(128) PRIMARY KEY, generation VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL
);
