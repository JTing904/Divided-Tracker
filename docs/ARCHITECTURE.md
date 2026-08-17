# Architecture

## The one decision everything else follows from

A dividend counter that visibly moves every second could be built by writing a value to the
database on a timer. That would be wrong at any real scale: N users × M holdings × 1 write
per second, to display a number that is entirely predictable from data already stored.

So the database stores the **parameters of a straight line**, and every observer evaluates
that line at the current instant.

```
                 stored once per change              evaluated on every read
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│ expected_amount              │            │ accrued(t) =                       │
│ accumulation_start           │  ────────► │   clamp(elapsed(t) × rate,         │
│ accumulation_end             │            │         0, expected_amount)        │
│ rate_per_second              │            └────────────────────────────────────┘
└──────────────────────────────┘
        dividend_transactions                 backend AND Android, identically
```

Consequences, all of which the product needs:

* **Reads are cheap.** `/api/dividends/live` is one indexed `SELECT` and zero writes, so the
  dashboard can refresh freely.
* **It works offline.** The client has the parameters; it does not need the server to keep
  counting.
* **Restarts resume correctly.** Nothing is being *counted*, so there is no count to lose.
  Close the app for a week and it reopens showing the right figure.
* **No jump on refresh.** Client and server evaluate the same function, so a response
  landing mid-tick does not visibly correct the number.

The requirement "when the user closes and reopens the app, the value must continue from the
correct timestamp" is not a feature bolted on here — it is a property of deriving instead of
accumulating.

## Why the window is the earning period

`accumulation_start = payment_date − frequency.accumulationDays` (182 for semi-annual, 91
for quarterly, …), not the ex-date.

A semi-annual RM320 dividend is *earned* across roughly half a year. Pacing it over the
~3 weeks between ex-date and payment would produce a rate ~9× too fast and a counter that
means nothing. Over 182 days it gives ≈ RM0.0000203/sec — which is what the product brief's
own worked example implies, and what the app shows.

Ex-date and record date are still stored and displayed; they govern *entitlement*, not
pacing.

## What the market data provider does and does not know

`MARKET_DATA_PROVIDER=yahoo` fetches from Yahoo Finance's chart and search endpoints. They
are unauthenticated and undocumented — the ones Yahoo's own site calls — which is a
deliberate trade-off: it is the only free source with usable Bursa Malaysia coverage, and it
carries no service guarantee.

Yahoo reports exactly two things per dividend: the **ex-date** and the **amount**. Everything
else in a dividend cycle has to be derived, and each derived value is tagged so it can be
labelled rather than passed off as fact.

| field | where it comes from |
|---|---|
| price | reported |
| ex-date | reported |
| dividend per share | reported |
| frequency | **inferred** from the median gap between ex-dates |
| payment date | **estimated** as ex-date + `payment-lag-days` (default 30) |
| record date | **unavailable** — stored as null rather than guessed |
| upcoming cycle | **projected** from the last amount at the inferred cadence |

`dividends.source` records which: `yahoo` for a cycle whose ex-date and amount were reported,
`yahoo-projected` for one this application forecast. Projected cycles are excluded from the
yield, so a guess never launders itself into a published figure.

The projection exists because the product is a live counter: with no future cycle there is
nothing to accumulate towards. It is only added when the reported history has run out, and
never with a payment date already in the past.

### Yield

Trailing twelve months of dividends actually paid, divided by price. The obvious shortcut —
most recent declaration × frequency — overstates or understates nearly every real payer,
because amounts vary between cycles and special dividends exist. The old formula was that
shortcut; it reported 6.35% for Maybank from a single RM0.32 declaration.

## Money

`BigDecimal` everywhere. Never `Double`, `Float`, or a JSON number.

| value | scale | why |
|---|---|---|
| amounts | 2 | currency |
| dividend per share | 8 | sub-cent declarations |
| rate per second | 12 | tiny, then multiplied by ~10⁷ seconds |
| displayed estimate | 8, **DOWN** | never overstate unpaid money |

JSON carries amounts as **strings**. A JSON number would be parsed into a double by most
clients and `0.000020350020` would not survive the round trip — reintroducing exactly the
error the `BigDecimal` discipline exists to prevent. `PlainBigDecimalSerializer` (backend)
and `BigDecimalSerializer` (Android) enforce this at the edges.

Three concepts are kept strictly distinct and never summed together:

* **expected** — `shares × dividend_per_share`, an estimate
* **accrued** — the live figure, an estimate of progress toward the expected amount
* **received** — `paid_amount`, set only on settlement; the only field history sums

The UI states this in words on the dashboard, not just in the data model.

## Backend

```
com.dividendstream.api
├─ common/        Money, ApiException, GlobalExceptionHandler, AuditableEntity
├─ config/        Security, Cache, Jackson, Clock, typed @ConfigurationProperties
├─ security/      JwtService, JwtAuthenticationFilter, AuthRateLimitFilter, AuthPrincipal
├─ auth/          register / login / refresh / logout, hashed refresh tokens
├─ user/
├─ stock/         local mirror of instruments
├─ marketdata/    StockDataProvider  ← the replaceable seam
├─ portfolio/     holdings
└─ dividend/      DividendAccumulationEngine  ← pure maths, unit tested
                  DividendTransactionService  ← the only writer of accumulation params
                  LiveDividendService         ← read side, zero writes
                  DividendScheduler           ← lifecycle transitions only
```

`DividendAccumulationEngine` takes no dependencies — not even a clock. Every result is a
function of its arguments, which is what allows the Android client to reimplement it and
stay in agreement. Both test suites assert the same behaviours deliberately.

### Scheduled jobs

Nothing recomputes accrued values. The jobs handle only genuine state changes:

| job | period | work |
|---|---|---|
| `advanceDividendStatuses` | 5 min | UPCOMING → ACCUMULATING → PAYABLE → PAID, index-backed |
| `refreshMarketData` | 15 min | prices, newly declared dividends, resync affected holders |
| `purgeExpiredRefreshTokens` | nightly | housekeeping |

### Caching

Everything goes through Spring's `CacheManager`. `spring.cache.type=caffeine` locally,
`redis` in production — configuration only, no code change. The live counter never reaches
the cache layer at all, because it never reaches a provider.

### Security

* JWT access tokens (15 min) + rotating refresh tokens, stored only as SHA-256 hashes
* BCrypt cost 12; login hashes a dummy value on unknown accounts so timing cannot enumerate
* every user-scoped query filters on the JWT's user id — path ids are never trusted alone
* per-IP fixed-window throttle on `/api/auth/**`
* `JWT_SECRET` required outside local/dev/test; the app refuses to boot without it
* error responses carry a stable `code` and safe prose; no stack traces or SQL escape

## Android

```
com.dividendstream.app
├─ core/          Money formatting, ServerClock, AppResult/AppError, serializers
├─ domain/        AccumulationCalculator  ← mirror of the backend engine, unit tested
├─ data/
│   ├─ remote/    Retrofit API, DTOs, token interceptor + refresh authenticator
│   ├─ local/     SessionStore, SnapshotCache (offline)
│   └─ repository/
└─ ui/            theme, components, one package per screen (ViewModel + Composable)
```

MVVM: every screen is a `ViewModel` exposing a `StateFlow<UiState>` plus a stateless
composable. No business logic, formatting decisions or network calls live inside a
`@Composable`.

### Dependency injection

Manual, via `AppContainer`. The graph is a handful of singletons built once at startup; a DI
framework would add KSP, a plugin and version coupling for no testability gain — every
ViewModel already takes plain constructor arguments. `AppViewModelProvider` is the single
place a framework would slot in.

### Clock skew

`/api/dividends/live` returns `serverTime`. The client stores `serverTime − deviceTime` and
evaluates the accumulation against the corrected clock, so a device with a wrong system time
still shows the server-correct figure. The offset is cached with the offline snapshot so a
cold, offline start is also correct.

### The counter itself

`rememberAccruedAmount` recomputes from the clock once per frame via `withFrameNanos` — not
a timer incrementing a running total. Frame-driven means it is smooth rather than stepping
once a second, and it costs nothing while the app is backgrounded because a non-drawing
window never resumes the coroutine. Countdowns use a separate 1 Hz ticker so they do not
drag a whole card to frame rate.

Monospace digits are a functional choice: with proportional glyphs the number visibly
jitters as digits change width.

### Offline

`SnapshotCache` keeps the last live and portfolio responses. On a network failure the app
shows the saved copy, **labelled stale with its age**, and the counter keeps running from the
stored timestamps. Authentication failures are never masked this way — those must reach the
UI so the session ends.

## Local development without Docker

`bootTestRun` starts the real application against a PostgreSQL server launched in-process
(Zonky). Tests and local runs therefore exercise the same engine, dialect and migrations as
production. The dependency lives in the test source set only, so the production classpath is
unaffected.

## Where the unbuilt features plug in

| feature | seam |
|---|---|
| real market data | implement `StockDataProvider`, set `MARKET_DATA_PROVIDER` |
| Redis cache | `CACHE_TYPE=redis`; no code change |
| notifications | `DividendScheduler` already detects the transitions worth notifying on |
| charts | `/api/dividends/history` already returns monthly and per-stock totals |
| multi-currency | `currency` is carried on every row and DTO; add conversion at the read side |
| Room / richer offline | replace `SnapshotCache` behind the repositories |
| distributed rate limiting | `AuthRateLimitFilter` is the same `INCR`+`EXPIRE` shape Redis wants |

## Known follow-ups

* Session tokens sit in app-private DataStore, protected by file-based encryption rather
  than the keystore. Access tokens are short-lived and refresh tokens revocable, so this is
  a reasonable MVP position — worth hardening before release.
* `paid_amount` is copied from the expected amount on settlement because the mock provider
  cannot report actual credited cash. A real broker feed must supply the true figure; the
  fields are already distinct so only the writer changes.
* `backfill-settled-cycles` assumes a newly added position was held through recent cycles,
  which populates history immediately but is an assumption, not a fact. Turn it off once
  ownership dates can be established.
* Mock dividend rates are illustrative; some yields are higher than the real securities'.
