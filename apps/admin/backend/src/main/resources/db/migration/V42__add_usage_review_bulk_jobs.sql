-- Freeze the target IDs before administrator confirmation. Later receipts never
-- join an already confirmed operation. Row outcomes survive worker restarts.
ALTER TABLE usage_review_queue ADD COLUMN review_job_id UUID;
CREATE INDEX idx_usage_review_pending_time ON usage_review_queue(status, occurred_at, user_id);
CREATE TABLE usage_review_bulk_jobs (
    id UUID PRIMARY KEY,
    requested_by UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('ARCHIVE', 'IMPORT')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'QUEUED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED')),
    user_id UUID,
    from_at TIMESTAMP WITH TIME ZONE,
    to_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    note VARCHAR(500),
    total_count INTEGER NOT NULL DEFAULT 0,
    excluded_count INTEGER NOT NULL DEFAULT 0,
    processed_count INTEGER NOT NULL DEFAULT 0,
    succeeded_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80)
);
CREATE INDEX idx_usage_review_jobs_status ON usage_review_bulk_jobs(status, created_at);
CREATE TABLE usage_review_bulk_items (
    job_id UUID NOT NULL REFERENCES usage_review_bulk_jobs(id) ON DELETE CASCADE,
    review_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    error_code VARCHAR(80),
    PRIMARY KEY (job_id, review_id)
);
CREATE INDEX idx_usage_review_job_items_pending ON usage_review_bulk_items(job_id, status, review_id);
