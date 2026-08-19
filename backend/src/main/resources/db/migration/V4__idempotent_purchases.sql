-- Lets a purchase be sent again without being applied twice.
--
-- The client queues a purchase when the server is asleep and retries until it lands, which is
-- only safe if the server can recognise a repeat. Without this, a reply lost on the way back --
-- a timeout, a dropped connection -- would leave the client resending an intent that had
-- already been carried out, and buying the shares twice. Money must not depend on the reply
-- arriving.
--
-- Keyed by the client's own identifier for the intent, scoped to the user so one person's key
-- can never resolve to another's holding.
CREATE TABLE purchase_intents (
    idempotency_key UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    holding_id      UUID        NOT NULL REFERENCES holdings (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_purchase_intents_user ON purchase_intents (user_id);
