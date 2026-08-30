-- Which day of its period a recurring flow actually pays on.
--
-- Money arrives in lumps on a date. Without this the engine can only assume a period pays when
-- it finishes, which is right for a daily allowance and wrong for a wage: somebody paid on the
-- 28th reads as having nothing until the 1st, so the four days they are best off are the four
-- days the app says they are broke.
--
-- Nullable, and null keeps the old meaning: pay at the end of the period. Every row that exists
-- when this runs was created under that rule, so leaving them null changes none of their
-- figures.
--
-- Meaning depends on the flow's period:
--   WEEKLY   1-7   ISO day of week, Monday is 1
--   MONTHLY  1-31  day of the month, clamped to months that are shorter
--   DAILY          not used -- a day's money arrives when the day is over
--   YEARLY         not used -- a year pays on its last day
ALTER TABLE cash_flows
    ADD COLUMN arrives_on SMALLINT;

ALTER TABLE cash_flows
    ADD CONSTRAINT cash_flows_arrives_on_range
        CHECK (arrives_on IS NULL OR (arrives_on BETWEEN 1 AND 31));
