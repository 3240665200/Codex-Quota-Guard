CREATE TABLE desktop_update_releases (
    id UUID PRIMARY KEY,
    version VARCHAR(32) NOT NULL UNIQUE,
    version_major INTEGER NOT NULL CHECK (version_major >= 0),
    version_minor INTEGER NOT NULL CHECK (version_minor >= 0),
    version_patch INTEGER NOT NULL CHECK (version_patch >= 0),
    notes VARCHAR(4000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by UUID NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE desktop_update_artifacts (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES desktop_update_releases(id) ON DELETE CASCADE,
    target VARCHAR(16) NOT NULL CHECK (target IN ('windows','darwin','linux')),
    arch VARCHAR(16) NOT NULL CHECK (arch IN ('x86_64','aarch64')),
    filename VARCHAR(200) NOT NULL,
    storage_name VARCHAR(80) NOT NULL UNIQUE,
    signature TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    uploaded_by UUID NOT NULL,
    UNIQUE(release_id,target,arch)
);
CREATE INDEX desktop_update_platform_idx ON desktop_update_artifacts(target,arch,release_id);
