CREATE TABLE play_token_bindings (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    superseded BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO play_token_bindings(token_hash,user_id) SELECT token_hash,user_id FROM play_purchases;

CREATE TABLE purchase_attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL,
    encrypted_token TEXT NOT NULL,
    payment_state VARCHAR(16) NOT NULL,
    activation_state VARCHAR(16) NOT NULL,
    period_hash VARCHAR(64),
    plan_id VARCHAR(16),
    expires_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(64),
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(user_id,token_hash)
);
CREATE INDEX idx_purchase_attempt_due ON purchase_attempts(next_attempt_at,lease_until);
CREATE INDEX idx_purchase_attempt_user ON purchase_attempts(user_id,id);

CREATE TABLE purchase_events (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES purchase_attempts(id),
    user_id UUID NOT NULL REFERENCES users(id),
    period_hash VARCHAR(64) NOT NULL UNIQUE,
    plan_id VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(attempt_id,period_hash)
);
CREATE INDEX idx_purchase_events_user ON purchase_events(user_id,id);
