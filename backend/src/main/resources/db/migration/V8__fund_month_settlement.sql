-- Banking a finished month's share as a real movement, instead of working it out again on
-- every read.
--
-- The share was derived: "this fund's percentage of every month that has already finished",
-- recomputed from the flows as they stand today. That was the right call while a fund's
-- balance grew continuously -- nothing had to be written per second, and a free-tier server
-- that sleeps could never miss a monthly job it did not have.
--
-- It stopped being the right call when the balance started stepping. A fund now moves once a
-- month, and a balance that jumps by RM40 with nothing in its own history to account for it is
-- a number nobody can check. Worse, deriving it meant it was never settled: changing a salary
-- in October silently rewrote what August had put aside.
--
-- So a finished month is banked once, as a row, and read back like any other. The write
-- happens on the first read that notices the month is over -- not on a schedule, for the same
-- reason as before -- and at most once per fund per month.
--
--   source          HAND told apart from MONTHLY_SHARE, so "put in by hand" stays honest and
--                   a settlement row can be labelled as what it is.
--   settled_month   Which month a MONTHLY_SHARE row banks, as YYYY-MM. Null for anything a
--                   person did themselves.
ALTER TABLE fund_movements
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'HAND';

ALTER TABLE fund_movements
    ADD COLUMN settled_month VARCHAR(7);

ALTER TABLE fund_movements
    ADD CONSTRAINT ck_fund_movements_source CHECK (source IN ('HAND', 'MONTHLY_SHARE'));

-- A settlement row must name the month it banks, and a hand-made one must not.
ALTER TABLE fund_movements
    ADD CONSTRAINT ck_fund_movements_settled_month
        CHECK (
            (source = 'MONTHLY_SHARE' AND settled_month IS NOT NULL) OR
            (source = 'HAND' AND settled_month IS NULL)
        );

-- The whole safety of settling on read rests on this: two requests arriving together cannot
-- bank August twice, because the second insert loses.
CREATE UNIQUE INDEX ux_fund_movements_settlement
    ON fund_movements (fund_id, settled_month)
    WHERE settled_month IS NOT NULL;
