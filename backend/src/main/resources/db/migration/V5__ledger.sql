-- The ledger: money that arrives and leaves outside the market.
--
-- Two tables with deliberately different jobs, and they must not be confused for one another.
--
-- `cash_flows` holds the recurring figures a person declares once -- a salary, an allowance,
-- rent, a subscription. This is what the per-second counter is derived from. Nothing is ever
-- written per second: the rate is amount / period, and the value on screen is a function of
-- the clock, exactly as it is for dividends.
--
-- `ledger_entries` holds what actually happened on a given day, one row per record. These are
-- facts. They never drive the counter, because mixing a projection with a record would leave
-- the person unable to tell which of the two numbers they were looking at.
--
-- Both are keyed by an id the client may supply, so a save sent twice -- a double tap, a retry
-- after a reply went missing -- records one row rather than two. Money must not depend on the
-- reply arriving.

CREATE TABLE cash_flows (
    id         UUID           PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(80)    NOT NULL,
    direction  VARCHAR(16)    NOT NULL,
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    -- DAILY exists as much as MONTHLY: a student with an allowance has no monthly salary to
    -- enter, and forcing one to be converted by hand would be the app doing arithmetic at the
    -- user instead of for them.
    period     VARCHAR(16)    NOT NULL,
    category   VARCHAR(40),
    currency   VARCHAR(3)     NOT NULL DEFAULT 'MYR',
    starts_on  DATE           NOT NULL,
    ends_on    DATE,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_cash_flows_direction CHECK (direction IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_cash_flows_period CHECK (period IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT ck_cash_flows_ends_after_starts CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX ix_cash_flows_user ON cash_flows (user_id);

CREATE TABLE ledger_entries (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    occurred_on DATE           NOT NULL,
    direction   VARCHAR(16)    NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    category    VARCHAR(40),
    note        VARCHAR(200),
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('INCOME', 'EXPENSE'))
);

-- Every read of this table is "one user, one month", newest first.
CREATE INDEX ix_ledger_entries_user_date ON ledger_entries (user_id, occurred_on DESC);

-- Where the money left over is going.
--
-- A fund holds a share, not an amount: "30% of whatever is left". Storing a percentage rather
-- than a figure is what lets a fund fill in real time alongside the counter, and what stops it
-- from quietly going stale the month a salary changes.
--
-- The shares of one person's funds may not exceed 100%, which is checked in the service rather
-- than here: the constraint spans rows, and expressing it in SQL would mean a trigger doing
-- work that belongs where the error message can be a sentence a person understands.
CREATE TABLE funds (
    id         UUID          PRIMARY KEY,
    user_id    UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(80)   NOT NULL,
    percent    NUMERIC(5, 2) NOT NULL,
    -- Names one of the client's built-in icons. Never a path or a URL.
    icon       VARCHAR(40),
    position   INTEGER       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_funds_percent CHECK (percent > 0 AND percent <= 100)
);

CREATE INDEX ix_funds_user ON funds (user_id);
