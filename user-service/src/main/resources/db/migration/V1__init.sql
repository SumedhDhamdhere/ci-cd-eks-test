CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255)        NOT NULL,
    name        VARCHAR(255)        NOT NULL,
    phone       VARCHAR(255),
    role        VARCHAR(50)         NOT NULL DEFAULT 'USER',
    active      BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP           NOT NULL DEFAULT NOW()
);
