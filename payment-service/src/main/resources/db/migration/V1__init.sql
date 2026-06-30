CREATE TABLE IF NOT EXISTS payments (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    status         VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    transaction_id VARCHAR(255),
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);
