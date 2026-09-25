CREATE TABLE ux_account (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(24) NOT NULL UNIQUE,
 display_name VARCHAR(24) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL
) AUTO_INCREMENT=4000000000000000;

CREATE TABLE ux_session (
 token_hash CHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 csrf_token CHAR(64) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL,
 expires_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE INDEX ux_session_expiry ON ux_session(expires_at);
CREATE INDEX ux_session_user ON ux_session(user_id, created_at);

CREATE TABLE ux_storefront (
 shop_id BIGINT PRIMARY KEY,
 category VARCHAR(24) NOT NULL,
 area VARCHAR(40) NOT NULL,
 address VARCHAR(200) NOT NULL,
 image_path VARCHAR(200) NOT NULL,
 published BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(shop_id) REFERENCES ux_shop(id)
);

CREATE TABLE ux_offer (
 activity_id BIGINT PRIMARY KEY,
 shop_id BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL,
 face_value_cents INT NOT NULL,
 terms VARCHAR(1000) NOT NULL,
 FOREIGN KEY(activity_id) REFERENCES ux_activity(id),
 FOREIGN KEY(shop_id) REFERENCES ux_shop(id),
 CHECK(face_value_cents>=0)
);
CREATE INDEX ux_offer_shop ON ux_offer(shop_id, activity_id);
CREATE INDEX ux_customer_requests ON ux_request(user_id, created_at, id);
