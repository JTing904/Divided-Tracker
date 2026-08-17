# Dividend Stream

Turns an expected stock dividend into a live, per-second accumulation you can watch.

Instead of "Expected Dividend: RM320", the app shows

```
YOUR DIVIDEND IS GROWING
RM 289.297390
Rate: RM0.00002035 / sec
```

> **This is a visualisation of an *expected* dividend accruing over time.** It is not a claim
> that the stock or your broker pays you every second, and an accumulating figure is not money
> received. Received income is tracked separately, and only from settled payments.

---

## Repository layout

```
backend/    Kotlin + Spring Boot REST API, PostgreSQL, Flyway  (source of truth)
android/    Kotlin + Jetpack Compose app, MVVM                 (Android-first client)
docs/       Architecture notes
```

The two are independent Gradle builds sharing only the REST contract.

## Requirements

| | Version | Notes |
|---|---|---|
| JDK | 21 | Android Studio bundles one at `<Android Studio>/jbr` |
| Android SDK | API 36 | `compileSdk`/`targetSdk` 36, `minSdk` 26 |
| PostgreSQL | 14+ | Only for real deployments — see below |
| Redis | any | Optional; Caffeine is used when Redis is absent |

Both builds use the Gradle wrapper, so Gradle itself does not need to be installed.

On Windows, point Gradle at JDK 21 explicitly:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

---

## Quick start on Windows

There are two applications, and they are the same application: a Windows desktop app and an
Android app, sharing one codebase and one backend.

### The desktop app

Install **`dist\DividendStream-1.0.1-installer.msi`** — a normal Windows installer that puts
the app in the Start Menu. It bundles its own Java runtime, so the machine needs no JDK and
no Gradle.

To rebuild it, run **`package-desktop.cmd`**. To run from source instead, double-click
**`run-desktop.cmd`**.

It is self-contained. PostgreSQL, the Spring Boot API and the Compose UI all run inside the
one process, so there is no backend window to start first and nothing to configure. Data
lives in `%LOCALAPPDATA%\DividendStream`.

### The Android app

Double-click **`run-all.cmd`**. It starts the backend in its own window, waits for it to
accept connections, boots an emulator if none is attached, then builds, installs and
launches the app.

| script | does |
|---|---|
| `run-desktop.cmd` | the Windows app, from source |
| `package-desktop.cmd` | builds `dist\DividendStream\Dividend Stream.exe` plus a Start Menu shortcut |
| `run-backend.cmd` | backend only, on port 8090, with the in-process database |
| `run-app.cmd` | emulator + `installDebug` + launch; assumes the backend is already up |
| `run-all.cmd` | backend and Android app, in order |
| `allow-phone-access.cmd` | opens port 8090 to your home network so a real phone can connect |

**The desktop app and `run-backend.cmd` both want port 8090, so run one or the other.** The
desktop app binds loopback only, which is why a phone cannot reach it — for a handset, use
`run-backend.cmd`, which binds all interfaces.

### On a real Android phone

`dist\DividendStream-phone-<ip>.apk` is built against this machine's LAN address. Copy it to
a phone on the same Wi-Fi and install it, run `allow-phone-access.cmd` once, and start the
backend with `run-backend.cmd`. If the PC's IP changes, rebuild:

```bash
cd android && ./gradlew assembleDebug -PapiBaseUrl=http://<new-ip>:8090/
```

Each script locates JDK 21 from the Android Studio install and the SDK from `ANDROID_HOME`
(falling back to `%LOCALAPPDATA%\Android\Sdk`), so neither needs to be on `PATH`.
`run-backend.cmd` also detects a leftover process holding port 8090 and offers to stop it,
which is the usual reason a second run fails to start.

There is no seeded account — register one in the app on first launch.

---

## Running the backend

### Local development — no Docker, no PostgreSQL install

```bash
cd backend
SERVER_PORT=8090 ./gradlew bootTestRun
```

`bootTestRun` starts the real application against a PostgreSQL server launched in-process,
so Flyway migrations and every `NUMERIC` guarantee run against the same engine production
uses. The embedded server lives in the test source set only; the production classpath stays
clean.

The API is then on `http://localhost:8090`.

### Against a real PostgreSQL

```bash
cd backend
cp .env.example .env      # then fill it in
DATABASE_URL=jdbc:postgresql://localhost:5432/dividend_stream \
DATABASE_USERNAME=dividend_stream \
DATABASE_PASSWORD=... \
JWT_SECRET=$(openssl rand -base64 48) \
./gradlew bootRun
```

`JWT_SECRET` is **required** outside the `local`/`dev`/`test` profiles — the app refuses to
start without it rather than silently signing tokens with a generated key.

### Tests

```bash
cd backend && ./gradlew test
```

---

## Running the Android app

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug      # with an emulator or device attached
```

The debug build points at `http://10.0.2.2:8090/` — the host machine as seen from an
emulator. Override it per build:

```bash
./gradlew assembleDebug -PapiBaseUrl=http://192.168.1.20:8090/
```

Cleartext HTTP is permitted **only** for `10.0.2.2` / `localhost` (see
`network_security_config.xml`); everything else must be HTTPS.

```bash
./gradlew testDebugUnitTest
```

---

## How the live counter works

The database is never written to on a per-second tick. Each `dividend_transactions` row
stores four parameters, set when a holding or a dividend changes:

| column | meaning |
|---|---|
| `expected_amount` | `shares × dividend_per_share` |
| `accumulation_start` | start of the period the dividend is earned over |
| `accumulation_end` | payment date |
| `rate_per_second` | `expected_amount / window_seconds`, at 12 decimal places |

Any observer then computes

```
accrued(t) = clamp(elapsed_seconds(t) × rate_per_second, 0, expected_amount)
```

Backend and Android run the identical formula, so:

* refreshing costs one indexed `SELECT` and no writes;
* the app keeps counting offline, from stored timestamps;
* closing and reopening resumes at the right value, because nothing is being *counted* —
  it is recomputed from the clock;
* the number never jumps when a refresh lands.

`/api/dividends/live` returns `serverTime`. The app stores `serverTime − deviceTime` and
ticks against the corrected clock, so a device with a wrong system time still shows the
right figure.

### Why the window is the earning period

A semi-annual RM320 dividend accrues across ~182 days (≈ RM0.0000204/sec), not across the
few weeks between ex-date and payment. Pacing it over the period the dividend is *earned*
is what makes the per-second figure meaningful.

---

## Money

Never floating point. `BigDecimal` throughout, and JSON carries every amount as a **string**
— a JSON number would be parsed as a double and destroy `0.000020350020`.

| value | scale |
|---|---|
| amounts | 2 |
| dividend per share | 8 |
| per-second rate | 12 |
| displayed estimate | 8, rounded **down** |

Estimates round down so an unpaid figure is never overstated. Three concepts stay strictly
separate: **expected dividend**, **estimated accumulation**, and **received income**
(`paid_amount`, the only thing history sums).

---

## Market data

All provider access goes through `StockDataProvider`. `MockStockDataProvider` ships with the
app and generates a Bursa Malaysia catalogue with dividend dates *relative to today*, so a
fresh install always has both an accumulating stream and settled history. Swap in a real
provider by implementing the interface and setting `MARKET_DATA_PROVIDER`.

**Provider credentials live in backend environment variables and are never sent to the app.**

---

## API

| | |
|---|---|
| `POST /api/auth/register` · `login` · `refresh` · `logout` | JWT access token + rotating refresh token |
| `GET`/`PUT /api/user/profile` | |
| `GET /api/stocks` · `/search` · `/{symbol}` | |
| `GET`/`POST /api/portfolio`, `PUT`/`DELETE /api/portfolio/{id}` | |
| `GET /api/dividends/live` | live snapshot + accumulation parameters |
| `GET /api/dividends/upcoming` · `history` · `{id}` | |

Every user-scoped query is filtered by the user id in the JWT, never by a path parameter, so
a guessed id resolves to nothing.

---

## Status

Delivered: authentication, portfolio, the dividend engine, the live dashboard, calendar and
history — end to end and tested.

Not yet built: push notifications, charts, a real market-data provider, and multi-currency.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for where each of those plugs in.
