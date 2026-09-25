ALTER TABLE ux_storefront ADD COLUMN city VARCHAR(60) NOT NULL DEFAULT '上海' AFTER category;

CREATE TABLE ux_experience_session (
 id BIGINT PRIMARY KEY,
 shop_id BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL,
 tags VARCHAR(240) NOT NULL,
 starts_at TIMESTAMP(3) NOT NULL,
 ends_at TIMESTAMP(3) NOT NULL,
 price_cents INT NOT NULL,
 capacity INT NOT NULL,
 available INT NOT NULL,
 published BOOLEAN NOT NULL DEFAULT TRUE,
 FOREIGN KEY(shop_id) REFERENCES ux_shop(id),
 CHECK(starts_at < ends_at),
 CHECK(price_cents >= 0),
 CHECK(available >= 0 AND available <= capacity)
);
CREATE INDEX ux_experience_search ON ux_experience_session(published, starts_at, ends_at, shop_id);

CREATE TABLE ux_planning_queue_quota (
 id TINYINT PRIMARY KEY,
 queued_count INT NOT NULL,
 queue_limit INT NOT NULL,
 CHECK(id = 1),
 CHECK(queued_count >= 0),
 CHECK(queue_limit > 0)
);
INSERT INTO ux_planning_queue_quota(id, queued_count, queue_limit) VALUES(1, 0, 100);

CREATE TABLE ux_planning_task (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 input_json JSON NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 result_json JSON NULL,
 error_code VARCHAR(64) NULL,
 attempts INT NOT NULL DEFAULT 0,
 deadline TIMESTAMP(3) NOT NULL,
 lease_owner VARCHAR(36) NULL,
 lease_until TIMESTAMP(3) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(user_id, request_key),
 FOREIGN KEY(user_id) REFERENCES ux_account(id),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','NEEDS_REFINEMENT','FAILED','TIMED_OUT','CANCELLED'))
);
CREATE INDEX ux_planning_ready ON ux_planning_task(status, deadline, created_at);
CREATE INDEX ux_planning_user ON ux_planning_task(user_id, created_at);
