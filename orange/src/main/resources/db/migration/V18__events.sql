-- The events inbox (v2 spec §13): every inbound event (GitHub webhooks today) is stored first
-- and processed afterwards, one per transaction in id order, so a slow or failing handler never
-- loses a delivery and a redelivered webhook is stored once (delivery_id is unique).
CREATE TABLE events (
    id           BIGSERIAL PRIMARY KEY,
    source       TEXT NOT NULL,
    type         TEXT NOT NULL,
    repo         TEXT,
    delivery_id  TEXT UNIQUE,
    payload      JSONB NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    attempts     INT NOT NULL DEFAULT 0,
    last_error   TEXT
);

CREATE INDEX idx_events_unprocessed ON events (id) WHERE processed_at IS NULL;

CREATE OR REPLACE FUNCTION notify_event_received() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('event_received', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_notify_received
    AFTER INSERT ON events
    FOR EACH ROW EXECUTE FUNCTION notify_event_received();
