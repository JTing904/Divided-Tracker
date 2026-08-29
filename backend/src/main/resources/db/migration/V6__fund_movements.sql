-- Money actually going into and out of a fund.
--
-- Without this a fund was only ever "your share of this month's surplus", which reset on the
-- first of every month and could not be spent. That is not a fund; it is a percentage with a
-- progress bar. A fund has a balance that survives the month, and money can leave it.
--
-- The rule the whole ledger is built on applies here too, and this table is the fact half of
-- it: a movement is something that happened. The accruing share shown beside it is a
-- projection and is never banked automatically -- the app cannot know whether the person
-- really moved the money, and inventing a deposit they did not make would put a number in
-- front of them that nothing in the world backs.
CREATE TABLE fund_movements (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Deleting a fund takes its movements with it: they have no meaning on their own, and
    -- leaving them behind would let a balance outlive the thing it was a balance of.
    fund_id     UUID           NOT NULL REFERENCES funds (id) ON DELETE CASCADE,
    occurred_on DATE           NOT NULL,
    direction   VARCHAR(16)    NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note        VARCHAR(200),
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_fund_movements_direction CHECK (direction IN ('DEPOSIT', 'WITHDRAWAL'))
);

-- Every read is "one fund, newest first", and the balance is a sum over one user's rows.
CREATE INDEX ix_fund_movements_fund ON fund_movements (fund_id, occurred_on DESC);
CREATE INDEX ix_fund_movements_user ON fund_movements (user_id);
