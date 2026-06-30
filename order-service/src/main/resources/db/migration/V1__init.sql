CREATE TABLE IF NOT EXISTS orders (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    status           VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    total_amount     NUMERIC(19,2) NOT NULL,
    shipping_address VARCHAR(255),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT        NOT NULL REFERENCES orders(id),
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(255)  NOT NULL,
    quantity     INT           NOT NULL,
    price        NUMERIC(19,2) NOT NULL
);
