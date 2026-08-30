-- Which month of the year a yearly flow pays in.
--
-- V7 gave a flow the day it pays on, and left a year out on the grounds that a year's payday
-- "would need a date rather than a number". That was true and it was not a reason to skip it:
-- a bonus paid every March 15th could only be entered as one paid on December 31st, which is
-- nine months of a person being told they have money they will not see until spring.
--
-- The day itself still lives in arrives_on. This column names the month it falls in, so the
-- two together are the date a year could not express alone.
--
--   YEARLY   1-12  month of the year, with arrives_on as the day within it
--   others         not used -- their period is shorter than a year and names its own day
--
-- Nullable, and null keeps V7's meaning: a year pays when it ends. Every row that exists when
-- this runs was created under that rule, so none of their figures move.
ALTER TABLE cash_flows
    ADD COLUMN arrives_month SMALLINT;

ALTER TABLE cash_flows
    ADD CONSTRAINT cash_flows_arrives_month_range
        CHECK (arrives_month IS NULL OR (arrives_month BETWEEN 1 AND 12));
