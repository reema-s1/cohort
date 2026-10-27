# Cohort

[![CI](https://github.com/reema-s1/cohort/actions/workflows/ci.yml/badge.svg)](https://github.com/reema-s1/cohort/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)

Cohort is a feature flag and A/B experimentation service written in Java. Teams create a flag,
roll it out to a percentage of users, run an experiment on it, and read which variant won.

Flags are evaluated **inside your application** by the Cohort Java SDK. A flag check is a hash and
a loop (about 230 ns), never a network call, and if the Cohort server goes down your app keeps
serving the last config it saw.

## Features

- **Local evaluation:** deterministic MurmurHash3 bucketing with allowlist, attribute and
  percentage rules. Every SDK instance gives the same answer with no shared state.
- **Safe rollouts:** ramping from 10% to 20% only adds users, there's a kill switch, and every
  change carries optimistic locking and a full audit log.
- **Instant updates:** the SDK polls with ETags and can also hold a server-sent events connection,
  so a kill switch can reach it in under a second.
- **Trustworthy results:** exactly-once counting on top of at-least-once delivery, first-exposure
  attribution, and both a fixed-sample p-value and one that stays valid however often you check.
- **Mutually exclusive experiments:** flags in the same layer never share a user.
- **Operable:** an admin console, OpenAPI docs, Prometheus metrics and a Grafana dashboard.

## Architecture

```mermaid
flowchart LR
    subgraph app[Your application]
        sdk[Cohort Java SDK<br/>local evaluation<br/>atomic snapshot<br/>disk fallback<br/>event buffer]
    end
    eng([Product engineer]) -- flags, kill switch, results --> server
    sdk -- "poll config every 10 s (ETag, mostly 304)" --> server[Cohort server<br/>Spring Boot]
    server -. "SSE: new config version" .-> sdk
    sdk -- batched events --> server
    server <--> pg[(PostgreSQL<br/>flags, rules, audit log,<br/>experiment stats)]
    server -- publish --> kafka[[Kafka<br/>cohort.events]]
    kafka --> agg[Aggregator<br/>one consumer per partition<br/>dedup by event id]
    agg -- upsert stats --> pg
    server -. metrics .-> prom[Prometheus + Grafana]
```

## Quick start

You need Docker and JDK 21 or newer.

```sh
docker compose up -d --build                      # Postgres, Kafka, server, Prometheus, Grafana
mvn -pl cohort-simulator -am install -DskipTests
mvn -pl cohort-simulator exec:java                # 100,000 simulated users through the real SDK
```

The simulator creates a `new-search-ranking` experiment. It sends traffic in which control converts
at 10% and treatment at 11%, then prints the results. These come from a real run; the counts are
cumulative over two runs:

```
variant         exposures  conversions       rate       lift      p-value       seq. p
control           100,128        9,987      9.97%          -            -            -
treatment          99,872       10,912     10.93%      +9.5%     3.50e-12     3.54e-10
```

| URL | What's there |
|---|---|
| http://localhost:8080 | Admin console: flags, kill switch, results, audit log |
| http://localhost:8080/swagger-ui.html | REST API documentation |
| http://localhost:3000 | Grafana dashboard (anonymous viewer) |
| http://localhost:9090 | Prometheus |

The development API keys are `dev-admin-key` for the admin API and `dev-sdk-key` for the SDK API.
Override them with `COHORT_ADMIN_KEY` and `COHORT_SDK_KEY`.

## Using the SDK

```java
CohortClient cohort = CohortClient.builder()
    .serverUrl("http://localhost:8080")
    .apiKey(System.getenv("COHORT_KEY"))
    .pollInterval(Duration.ofSeconds(10))
    .streaming(true)                     // optional: push updates over SSE
    .build();

CohortUser user = CohortUser.of("user-123").with("country", "IN");

String variant = cohort.getVariant("new-search-ranking", user);   // "control" or "treatment"
boolean on = cohort.isEnabled("dark-mode", user, false);           // false is the fallback
cohort.track("new-search-ranking", user, "applied_to_job");        // record a conversion

new CohortMetrics(cohort).bindTo(meterRegistry);                   // optional Micrometer metrics
```

A flag check never throws. If the SDK has no config at all, it returns the default you passed in.

## Defining a flag

```json
{
  "key": "new-search-ranking",
  "defaultVariant": "control",
  "variants": [{"name": "control", "weight": 5000}, {"name": "treatment", "weight": 5000}],
  "rules": [
    {"priority": 1, "type": "ALLOWLIST", "condition": {"userIds": ["qa-1"]}, "variant": "treatment"},
    {"priority": 2, "type": "ATTRIBUTE", "condition": {"attribute": "country", "values": ["IN"]}, "variant": null},
    {"priority": 3, "type": "PERCENT", "condition": {"basisPoints": 1000}, "variant": null}
  ],
  "layer": {"name": "search", "start": 0, "end": 5000}
}
```

- Rules run in priority order, and the first one that matches wins.
- A rule with a `variant` always serves that variant. A rule with `"variant": null` serves the
  weighted split and puts the user into the experiment.
- Users who match no rule get `defaultVariant`.
- Weights are in basis points and must add up to 10,000.
- `layer` is optional. Flags in the same layer must claim slices of its 10,000 buckets that don't
  overlap; the API rejects an overlap with 409. Users outside a flag's slice get `defaultVariant`,
  but allowlist rules still apply to them, so QA can see every variant.

## API

**Admin API** (`X-API-Key: <admin key>`; optional `X-Actor` header for the audit log)

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/flags` | Create a flag |
| `GET /api/v1/flags` · `GET /api/v1/flags/{key}` | List or read flags |
| `PUT /api/v1/flags/{key}` | Replace a flag; send the `version` you read, or get 409 if it changed |
| `POST /api/v1/flags/{key}/kill` | Kill switch: turn the flag off for everyone |
| `DELETE /api/v1/flags/{key}` | Archive a flag; it drops out of the SDK config |
| `GET /api/v1/flags/{key}/audit` | Who changed what and when, with before and after |
| `GET /api/v1/experiments/{key}/results` | Exposures, conversions, rate, lift and p-values per variant |

**SDK API** (`X-API-Key: <sdk key>`)

| Endpoint | Purpose |
|---|---|
| `GET /sdk/v1/config` | Full config; `If-None-Match` with the current ETag returns 304 |
| `GET /sdk/v1/stream` | Server-sent events: the config version on connect and after each change |
| `POST /sdk/v1/events` | Send up to 500 exposure and conversion events |
| `POST /sdk/v1/evaluate` | Server-side evaluation for clients that can't run the SDK |

## How it works

### Assignment

- **Hash, don't store.** A user's bucket is `murmur3_32(salt + ":" + userId) mod 10000`, mapped to
  a variant by walking the cumulative weights. Because this is a pure function, assignment is
  sticky without a database lookup.
- **Per-flag salt.** Without a salt, the same users would land in treatment of every experiment.
  A test checks that two 50/50 flags put about 25% of users in both treatments, as independent
  assignments should.
- **Separate rollout and split hashes.** A `PERCENT` rule hashes `salt:rollout`, while the split
  hashes `salt`. With a single hash, a 10% rollout of a 50/50 split would put everyone in control.
- **Ramping only adds users.** Going from 10% to 20% adds buckets 1000–1999, and nobody who was
  already in drops out.
- **Layers.** Flags in a layer bucket users by the layer's name, so they all agree on which slice a
  user is in. Within its slice, each flag still splits by its own salt.

### SDK resilience

- **Fail-safe order:** in-memory snapshot, then the snapshot on disk (if the app starts while the
  server is down), then the caller's default.
- **Atomic snapshots.** The config is an immutable record behind an `AtomicReference`. Readers never
  lock, and older versions are ignored, so a lagging server instance can't roll an SDK back.
- **Polling, with push as a hint.** Most polls are a 304 with an empty body. The SSE stream carries
  only a version number, and the SDK then fetches the config the normal way. If the stream drops,
  changes still arrive on the next poll.
- **Analytics never slow the app.** Events go into a bounded queue that a virtual thread flushes
  every 5 seconds or every 500 events. When the queue is full, events are dropped and counted;
  the app never blocks.

### Counting and statistics

- **Exactly-once counts.** Every event has a UUID, and a `seen_events` primary key turns SDK retries
  and Kafka redeliveries into no-ops. Kafka offsets are committed only after the database
  transaction, so a crash replays events rather than losing them.
- **First exposure wins.** Each user counts once per flag, in the variant they were first exposed
  to, and converts at most once. Only split assignments count as exposures, so QA users and users
  served the default never skew the results.
- **Set-based aggregation.** Each batch takes a few `unnest()` statements plus one upsert per
  variant. There is one consumer per partition (`COHORT_AGGREGATOR_CONSUMERS`, default 6), and
  stats rows are locked in key order, so consumers can't deadlock.
- **Two p-values.** The z-test p-value is valid only if you read it once, at a sample size you
  fixed in advance. The sequential (mSPRT) p-value stays valid however often you check it. In a
  simulated A/A test checked 20 times, the z-test declared a winner in over 10% of runs; the
  sequential test did so in under 5%.

### Consistency

- **Optimistic locking.** A write must carry the version it read. A row lock makes the version
  check and the increment atomic: in a test, eight concurrent writers produce exactly one success
  and seven 409s.
- **One transaction per change.** The flag write, its audit row and the global config version bump
  commit together. After that, the in-memory config cache is rebuilt and the new version is
  announced to streaming SDKs.

## Project layout

| Module | Contents |
|---|---|
| `cohort-common` | Flag model, bucketing, rule evaluator and validator, shared by server and SDK |
| `cohort-server` | Spring Boot service: admin API, SDK API, event ingest, aggregator, results, console |
| `cohort-sdk` | Java client: local evaluation, polling and streaming, disk snapshot, event buffer |
| `cohort-simulator` | Generates experiment traffic through the real SDK |
| `cohort-bench` | JMH micro-benchmarks of flag evaluation |

## Testing

```sh
mvn verify     # unit tests plus Testcontainers integration tests (needs Docker)
```

- **Evaluator:** rule order, targeting, weights, ramp stability, a 1,000,000-user distribution
  test (50/50 holds within 0.5%), and layer exclusivity across 100,000 users.
- **SDK:** ETag polling, kill switch propagation, server outages, offline restarts from disk,
  buffer overflow, SSE push and reconnects. These tests run against an in-process fake server.
- **Server:** CRUD, validation, concurrent writes, audit, layer overlap, ETag/304, the SSE stream,
  event deduplication and attribution. These run against real Postgres and Kafka.
- **Statistics:** the z-test against known values, and the sequential test against simulated
  A/A experiments that are checked repeatedly.

## Performance

Measured on a single laptop; methodology and raw output are in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

| Measurement | Result |
|---|---|
| SDK `getVariant` (JMH) | 233 ns average, 0.8 µs p99 |
| Config polling (k6, 50 virtual users) | 23,650 req/s, p95 4.5 ms, 0 errors |
| Event ingest (k6, 500-event batches) | 55,800 events/s, 0 errors |
| Aggregation (single consumer) | 9,250 events/s |

## Roadmap

- Per-user authentication for the admin API and console, replacing the shared key
- Multiple metrics per experiment
- CUPED variance reduction
- Change notifications between server instances (Postgres `LISTEN/NOTIFY`), so streams on every
  instance fire at once
