-- Dividend Stream :: initial schema.
--
-- Every monetary column is NUMERIC. Floating point is never used for money.
-- Scales: amounts 2, per-share 8, per-second rate 12 (a rate is a tiny fraction that
-- gets multiplied by a large elapsed-second count, so it needs the extra precision).

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    base_currency VARCHAR(3)   NOT NULL DEFAULT 'MYR',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
-- Case-insensitive uniqueness: Alice@x.com and alice@x.com are one account.
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
-- Only the SHA-256 of a refresh token is stored, so a database leak yields no usable token.
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE stocks (
    id               UUID         PRIMARY KEY,
    symbol           VARCHAR(32)  NOT NULL,
    company_name     VARCHAR(200) NOT NULL,
    exchange         VARCHAR(32)  NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    sector           VARCHAR(100),
    last_price       NUMERIC(19, 4),
    price_updated_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_stocks_exchange_symbol ON stocks (exchange, symbol);
CREATE INDEX ix_stocks_company_name ON stocks (lower(company_name));

CREATE TABLE holdings (
    id            UUID          PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stock_id      UUID          NOT NULL REFERENCES stocks (id) ON DELETE RESTRICT,
    quantity      NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    average_price NUMERIC(19, 4) NOT NULL CHECK (average_price >= 0),
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL
);
-- One position per stock per user; adding more shares updates the existing position.
CREATE UNIQUE INDEX ux_holdings_user_stock ON holdings (user_id, stock_id);
CREATE INDEX ix_holdings_user ON holdings (user_id);

CREATE TABLE dividends (
    id                 UUID           PRIMARY KEY,
    stock_id           UUID           NOT NULL REFERENCES stocks (id) ON DELETE CASCADE,
    dividend_per_share NUMERIC(19, 8) NOT NULL CHECK (dividend_per_share >= 0),
    currency           VARCHAR(3)     NOT NULL,
    frequency          VARCHAR(20)    NOT NULL,
    ex_date            DATE           NOT NULL,
    record_date        DATE,
    payment_date       DATE           NOT NULL,
    source             VARCHAR(32)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_dividends_window CHECK (payment_date > ex_date)
);
CREATE UNIQUE INDEX ux_dividends_stock_window ON dividends (stock_id, ex_date, payment_date);
CREATE INDEX ix_dividends_payment_date ON dividends (payment_date);

-- The accumulation parameters live here. This table is written when a dividend cycle is
-- created or a holding changes -- NEVER on a per-second tick. The displayed value is
-- derived from (rate_per_second, accumulation_start, accumulation_end) plus the clock.
CREATE TABLE dividend_transactions (
    id                 UUID           PRIMARY KEY,
    user_id            UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stock_id           UUID           NOT NULL REFERENCES stocks (id) ON DELETE RESTRICT,
    holding_id         UUID           REFERENCES holdings (id) ON DELETE SET NULL,
    dividend_id        UUID           NOT NULL REFERENCES dividends (id) ON DELETE CASCADE,
    shares             NUMERIC(19, 4) NOT NULL CHECK (shares > 0),
    dividend_per_share NUMERIC(19, 8) NOT NULL CHECK (dividend_per_share >= 0),
    expected_amount    NUMERIC(19, 2) NOT NULL CHECK (expected_amount >= 0),
    paid_amount        NUMERIC(19, 2),
    currency           VARCHAR(3)     NOT NULL,
    accumulation_start TIMESTAMPTZ    NOT NULL,
    accumulation_end   TIMESTAMPTZ    NOT NULL,
    rate_per_second    NUMERIC(24, 12) NOT NULL CHECK (rate_per_second >= 0),
    status             VARCHAR(20)    NOT NULL,
    payment_date       DATE           NOT NULL,
    paid_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_dividend_tx_status
        CHECK (status IN ('UPCOMING', 'ACCUMULATING', 'PAYABLE', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_dividend_tx_window CHECK (accumulation_end > accumulation_start)
);
-- A user accrues a given dividend cycle exactly once.
CREATE UNIQUE INDEX ux_dividend_tx_user_dividend ON dividend_transactions (user_id, dividend_id);
CREATE INDEX ix_dividend_tx_user_status ON dividend_transactions (user_id, status);
CREATE INDEX ix_dividend_tx_user_payment_date ON dividend_transactions (user_id, payment_date);
CREATE INDEX ix_dividend_tx_status_end ON dividend_transactions (status, accumulation_end);
