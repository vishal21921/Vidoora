-- Receipt journal only. Cached rows never authorize media/paid benefits without current verification.
CREATE TABLE play_purchases (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    encrypted_token TEXT NOT NULL,
    support_id UUID NOT NULL UNIQUE,
    plan_id VARCHAR(16),
    period_id VARCHAR(256),
    expires_at TIMESTAMP WITH TIME ZONE,
    state VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_play_purchases_user_updated ON play_purchases(user_id, updated_at);
