-- Where a payment date comes from, once anyone actually knows.
--
-- Yahoo reports an ex-date and an amount and nothing else, so every payment date in this table
-- has until now been this application's own estimate: the ex-date plus a fixed lag. That lag is
-- a guess about an issuer nobody measured. This column holds the date the money genuinely
-- arrived, as reported by someone it arrived for, which is both the truth for that cycle and
-- the evidence for estimating the next one.
--
-- A fact about the issuer rather than about one holder, so it is stored on the shared cycle:
-- the company paid on that day for everybody who held the shares.
ALTER TABLE dividends ADD COLUMN actual_payment_date DATE;

-- Money cannot arrive before the shares go ex.
ALTER TABLE dividends ADD CONSTRAINT ck_dividends_actual_after_ex
    CHECK (actual_payment_date IS NULL OR actual_payment_date > ex_date);

-- A cycle is identified by its ex-date. The payment date used to be part of that identity,
-- which was safe only while it never changed; now that a better estimate can replace an older
-- one, keying on it would file the revision as a second dividend and show the stock paying
-- twice. The application keys on this pair from here on.
--
-- Deliberately NOT unique. An earlier defect could leave two provider rows sharing an ex-date,
-- and a unique index would refuse to build on a database that has any -- failing the deploy
-- rather than the query. Clearing them first would mean deleting dividend rows, and deletion
-- cascades into settled entitlements, which carry the one figure here that must never be
-- inferred away. So the invariant is enforced in the upsert, and the index only makes the
-- lookup that enforces it cheap.
CREATE INDEX ix_dividends_stock_ex_date ON dividends (stock_id, ex_date);
