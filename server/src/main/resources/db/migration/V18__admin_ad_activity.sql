-- Client-reported SDK activity, never a revenue or payout ledger.
CREATE TABLE ad_sdk_events (
 request_id UUID NOT NULL,
 event VARCHAR(16) NOT NULL CHECK (event IN ('REQUEST','LOADED','IMPRESSION','CLICK','FAILED','SHOW_FAILED')),
 format VARCHAR(16) NOT NULL CHECK (format IN ('INTERSTITIAL','BANNER')),
 device_hash CHAR(64) NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(request_id,event)
);
CREATE INDEX ad_sdk_events_time ON ad_sdk_events(recorded_at);
ALTER TABLE creator_invites ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE creator_invites ADD COLUMN created_by UUID REFERENCES users(id);
CREATE INDEX creator_invites_generator ON creator_invites(created_by,created_at);
