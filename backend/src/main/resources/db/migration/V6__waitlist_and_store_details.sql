ALTER TABLE ux_storefront
  ADD COLUMN business_hours VARCHAR(80) NOT NULL DEFAULT '10:00-22:00',
  ADD COLUMN highlights VARCHAR(300) NOT NULL DEFAULT '',
  ADD COLUMN review_summary VARCHAR(500) NOT NULL DEFAULT '',
  ADD COLUMN service_notice VARCHAR(500) NOT NULL DEFAULT '';

CREATE TABLE ux_waitlist (
  id VARCHAR(36) PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  promoted_request_id VARCHAR(36),
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE(activity_id, user_id),
  UNIQUE(request_key),
  UNIQUE(promoted_request_id),
  FOREIGN KEY(activity_id) REFERENCES ux_activity(id),
  FOREIGN KEY(user_id) REFERENCES ux_account(id),
  FOREIGN KEY(promoted_request_id) REFERENCES ux_request(id),
  CHECK(state IN ('WAITING','PROMOTING','PROMOTED','CANCELLED','EXPIRED'))
);
CREATE INDEX ux_waitlist_ready ON ux_waitlist(activity_id, state, created_at, id);

