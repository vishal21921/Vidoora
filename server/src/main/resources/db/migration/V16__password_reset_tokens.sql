CREATE TABLE password_reset_tokens (
 token_hash VARCHAR(64) PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX reset_user_created ON password_reset_tokens(user_id,created_at);
