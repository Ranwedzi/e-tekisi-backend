-- V15: Driver documents (licence, PrDP, operating licence, ...). Files are
-- AES-256-GCM encrypted with DATA_ENCRYPTION_KEY before they reach the
-- database, and kept in their own table so listing documents never reads them.

CREATE TABLE IF NOT EXISTS driver_schema.driver_documents (
    id            BIGSERIAL PRIMARY KEY,
    driver_id     BIGINT       NOT NULL REFERENCES driver_schema.drivers (id),
    doc_type      VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED',
    file_name     VARCHAR(120) NOT NULL,
    content_type  VARCHAR(40)  NOT NULL,
    size_bytes    INT          NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    expires_on    DATE,
    review_note   VARCHAR(300),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at   TIMESTAMPTZ,
    reviewed_by   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_driver_documents_driver ON driver_schema.driver_documents (driver_id, doc_type, uploaded_at);
CREATE INDEX IF NOT EXISTS idx_driver_documents_expiry ON driver_schema.driver_documents (status, expires_on);

CREATE TABLE IF NOT EXISTS driver_schema.driver_document_files (
    document_id BIGINT PRIMARY KEY REFERENCES driver_schema.driver_documents (id) ON DELETE CASCADE,
    content     BYTEA  NOT NULL
);
