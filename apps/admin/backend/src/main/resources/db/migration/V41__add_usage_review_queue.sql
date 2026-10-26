-- Quarantine is separate from accounting. Only an explicit administrator action
-- creates a usage event; original timestamps and payloads are never rewritten.
CREATE TABLE usage_review_queue (
    id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    team_id UUID REFERENCES teams(id) ON DELETE SET NULL,
    official_account_id UUID REFERENCES official_accounts(id) ON DELETE SET NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by UUID,
    review_note VARCHAR(500),
    CHECK (status IN ('PENDING', 'ARCHIVED', 'IMPORTED'))
);
CREATE INDEX idx_usage_review_status_received ON usage_review_queue(status, received_at, id);
CREATE INDEX idx_usage_review_user_received ON usage_review_queue(user_id, received_at, id);
