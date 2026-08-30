# Deploying the backend to Cloud Run

Why this exists: on a free Render instance the application takes **79 seconds** to start, and
the instance stops after fifteen minutes of quiet, so the first request after a pause waits
about **104 seconds** end to end. Almost none of that is the database — Hikari connects in
0.5s and Flyway finds nothing to do in another 0.5s. It is CPU. The same jar starts in **9
seconds** on an ordinary laptop, and Render's free tier allocates a tenth of a core.

So the fix is a host that allocates a real one. Nothing in the code changes; the Dockerfile
already reads `PORT`, which is what Cloud Run injects.

Neon is untouched by any of this. The database stays exactly where it is, and both hosts can
talk to it at once, so there is no moment where the app is down and no data to move.

## Before you start

Have these to hand. None of them belongs in a chat window — they go from your password
manager straight into the Cloud Run console:

- The Neon connection details already set on Render (`DATABASE_URL`, `DATABASE_USERNAME`,
  `DATABASE_PASSWORD`). Copy them from Render's own environment page.
- `JWT_SECRET`, **the same value Render has**. A different one invalidates every session and
  signs everybody out.
- `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_SECRET`.

## Settings

Create the service from **Cloud Run → Deploy container → Continuously deploy from a
repository**, pointing at `JTing904/Divided-Tracker`, branch `main`.

| Setting | Value | Why |
|---|---|---|
| Build type | Dockerfile | The one already in the repository |
| Build context | `/backend` | The Dockerfile copies `gradlew`, `src` etc. relative to it |
| Dockerfile path | `/backend/Dockerfile` | |
| Region | `asia-southeast1` | Neon is in Singapore; every query crosses this gap |
| Authentication | Allow unauthenticated | It is a public API that does its own auth |
| CPU | 1 | The whole point of moving |
| Memory | 1 GiB | 512 MiB fits, barely; the headroom costs nothing at this usage |
| Startup CPU boost | **On** | Extra CPU precisely during the boot being paid for |
| Min instances | 0 | Above zero removes the wait entirely and stops being free |
| **Max instances** | **1** | See below — this one matters |
| Request timeout | 300s | Long enough that a cold start is never cut off |

### Why max instances is 1

Neon's free tier allows a limited number of connections, and every instance opens its own
Hikari pool. Left unbounded, a burst of traffic starts instances that exhaust the database
rather than serve the burst. One instance is also plenty: this is a handful of users, and the
live counter is computed on the client from stored timestamps rather than polled.

## Environment variables

The same set Render has, minus anything Render-specific:

```
DATABASE_URL                    jdbc:postgresql://<neon-host>/neondb?sslmode=require
DATABASE_USERNAME
DATABASE_PASSWORD
JWT_SECRET
MARKET_DATA_PROVIDER            yahoo
DIVIDEND_BACKFILL               false
INVITE_CODE
LATEST_CLIENT
GOOGLE_WEB_CLIENT_ID
GOOGLE_DESKTOP_CLIENT_ID
GOOGLE_DESKTOP_CLIENT_SECRET
```

`RENDER_GIT_COMMIT` has no counterpart here, so `/api/app/version` reports a null commit until
`dividend-stream.release.commit` is given something else to read. Not worth solving before the
move is known to work.

## What changes about behaviour

Cloud Run's default is to allocate CPU **only while a request is being handled**, so between
requests the container is frozen. The three `@Scheduled` jobs — advancing dividend statuses
every five minutes, refreshing prices every fifteen, purging expired refresh tokens nightly —
will not run on their own.

This is less of a loss than it sounds, because a free Render instance already stops after
fifteen idle minutes and those jobs only ran while somebody was using the app. But it is a real
difference, and the honest fix if prices start looking stale is to refresh them when they are
asked for and found old, rather than on a timer that assumes the process is always alive. That
is the right shape for a service that scales to zero anyway.

## Checking it worked, before trusting it with anything

`/api/app/version` is unauthenticated and reads no database, so it answers as soon as the
container is listening:

```bash
curl https://<new-url>/api/app/version
```

`commit` reports Cloud Run's revision name here rather than a SHA — Cloud Run sets
`K_REVISION` and nothing else, and `application.yml` falls back to it. It names no commit, but
it changes on every deploy, which is the only thing this field is ever consulted for.

Then time a cold start honestly. Wait for the instance to scale to zero, and:

```bash
curl -o /dev/null -s -w "%{time_total}s\n" https://<new-url>/api/app/version
```

Around 12 seconds is the move having worked. Around 100 means the CPU setting did not take.

## Cutting over

The desktop reads its server at runtime, from
`%LOCALAPPDATA%\DividendStream\config.properties`:

```properties
backend.url=https://<new-url>/
```

The phone has it baked in at build time, so switching it is a rebuild and a reinstall:

```bash
cd android && ./gradlew assembleDebug -PapiBaseUrl=https://<new-url>/
```

Both services can run at once against the same Neon database, so there is no cutover moment.
Move the desktop first — one line, no rebuild, and trivially reversible by deleting the
file. Live on it, then rebuild the phone.

## Turning Render off

Not until the phone has been rebuilt and used for a while. Until then Render is the only thing
an un-updated phone can talk to, and a phone that cannot reach its server cannot be fixed
remotely.

`LATEST_CLIENT` has to be set on Cloud Run too, or the update banner quietly stops appearing.

## What is still true afterwards

The cold start goes from about 104 seconds to about 12. It does not go to zero: that needs a
minimum instance, which is what costs money. If 12 seconds is still too long, the honest answer
is paying for an instance that never sleeps — on either host — rather than moving again.
