CREATE TABLE ux_reservation_epoch (
    activity_id BIGINT PRIMARY KEY,
    epoch VARCHAR(36) NOT NULL,
    FOREIGN KEY(activity_id) REFERENCES ux_activity(id)
);
