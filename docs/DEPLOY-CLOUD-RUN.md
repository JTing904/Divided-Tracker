# Deploying the backend to Cloud Run

Why this exists: on a free Render instance the application takes **79 seconds** to start, and
the instance stops after fifteen minutes of quiet, so the first request after a pause waits
about **104 seconds** end to end. Almost none of that is the database — Hikari connects in
0.5s and Flyway finds nothing to do in another 0.5s. It is CPU. The same jar starts in **9
seconds** on an ordinary laptop, and Render's free tier allocates a tenth of a core.

So the fix is a host that allocates a real one. Nothing in the code changes; the Dockerfile
already reads `PORT`, which is what Cloud Run injects.

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

## Afterwards

The new URL replaces `https://divided-tracker.onrender.com/` in the clients, which is baked at
build time:

```bash
cd android && ./gradlew assembleDebug -PapiBaseUrl=https://<new-url>/
```

Both services can run at once against the same Neon database, so there is no cutover moment —
point one client at the new URL, confirm it, then rebuild the rest.
