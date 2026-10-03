CREATE TABLE ux_order_projection (
 order_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX ux_projection_pending ON ux_order_projection(applied_revision, revision);
CREATE TABLE ux_order_read_00 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_00 ON ux_order_read_00(user_id,created_at,id);
CREATE TABLE ux_order_read_01 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_01 ON ux_order_read_01(user_id,created_at,id);
CREATE TABLE ux_order_read_02 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_02 ON ux_order_read_02(user_id,created_at,id);
CREATE TABLE ux_order_read_03 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_03 ON ux_order_read_03(user_id,created_at,id);
CREATE TABLE ux_order_read_04 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_04 ON ux_order_read_04(user_id,created_at,id);
CREATE TABLE ux_order_read_05 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_05 ON ux_order_read_05(user_id,created_at,id);
CREATE TABLE ux_order_read_06 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_06 ON ux_order_read_06(user_id,created_at,id);
CREATE TABLE ux_order_read_07 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_07 ON ux_order_read_07(user_id,created_at,id);
CREATE TABLE ux_order_read_08 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_08 ON ux_order_read_08(user_id,created_at,id);
CREATE TABLE ux_order_read_09 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_09 ON ux_order_read_09(user_id,created_at,id);
CREATE TABLE ux_order_read_10 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_10 ON ux_order_read_10(user_id,created_at,id);
CREATE TABLE ux_order_read_11 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_11 ON ux_order_read_11(user_id,created_at,id);
CREATE TABLE ux_order_read_12 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_12 ON ux_order_read_12(user_id,created_at,id);
CREATE TABLE ux_order_read_13 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_13 ON ux_order_read_13(user_id,created_at,id);
CREATE TABLE ux_order_read_14 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_14 ON ux_order_read_14(user_id,created_at,id);
CREATE TABLE ux_order_read_15 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_15 ON ux_order_read_15(user_id,created_at,id);
CREATE TABLE ux_order_read_16 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_16 ON ux_order_read_16(user_id,created_at,id);
CREATE TABLE ux_order_read_17 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_17 ON ux_order_read_17(user_id,created_at,id);
CREATE TABLE ux_order_read_18 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_18 ON ux_order_read_18(user_id,created_at,id);
CREATE TABLE ux_order_read_19 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_19 ON ux_order_read_19(user_id,created_at,id);
CREATE TABLE ux_order_read_20 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_20 ON ux_order_read_20(user_id,created_at,id);
CREATE TABLE ux_order_read_21 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_21 ON ux_order_read_21(user_id,created_at,id);
CREATE TABLE ux_order_read_22 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_22 ON ux_order_read_22(user_id,created_at,id);
CREATE TABLE ux_order_read_23 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_23 ON ux_order_read_23(user_id,created_at,id);
CREATE TABLE ux_order_read_24 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_24 ON ux_order_read_24(user_id,created_at,id);
CREATE TABLE ux_order_read_25 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_25 ON ux_order_read_25(user_id,created_at,id);
CREATE TABLE ux_order_read_26 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_26 ON ux_order_read_26(user_id,created_at,id);
CREATE TABLE ux_order_read_27 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_27 ON ux_order_read_27(user_id,created_at,id);
CREATE TABLE ux_order_read_28 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_28 ON ux_order_read_28(user_id,created_at,id);
CREATE TABLE ux_order_read_29 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_29 ON ux_order_read_29(user_id,created_at,id);
CREATE TABLE ux_order_read_30 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_30 ON ux_order_read_30(user_id,created_at,id);
CREATE TABLE ux_order_read_31 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL
);
CREATE INDEX ux_order_read_user_31 ON ux_order_read_31(user_id,created_at,id);
INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at)
 SELECT id,1,0,CURRENT_TIMESTAMP(3) FROM ux_order;
