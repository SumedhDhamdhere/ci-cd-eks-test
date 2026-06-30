CREATE TABLE IF NOT EXISTS inventory (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT UNIQUE NOT NULL,
    quantity   INT           NOT NULL,
    reserved   INT           NOT NULL DEFAULT 0,
    version    BIGINT
);
