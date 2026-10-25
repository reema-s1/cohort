# Benchmarks

Measured numbers only. Every figure below comes from a run on one laptop, so treat them as
orders of magnitude, not capacity planning.

**Machine:** Intel Core Ultra 5 125H (18 threads), 15 GB RAM, Windows 11, Docker Desktop.
JMH ran on JDK 24 on the host. The server, Postgres 16, Kafka 3.8 and k6 ran as containers on
the same Docker host, so the load generator competed with the server for CPU.

## Flag evaluation (JMH)

`cohort-bench/EvaluationBenchmark`, one thread, 3 x 2 s warmup, 5 x 2 s measurement. The flag has
a 100-id allowlist, an attribute rule and a 50% rollout into a 50/50 split; users cycle through
4096 ids so most fall through to the split (the most expensive path: two MurmurHash3 calls).

| Benchmark | Average | p50 | p99 |
|---|---|---|---|
| `Evaluator.evaluate` | 172 ns | 200 ns | 500 ns |
| `CohortClient.getVariant` (full SDK path) | 233 ns | 300 ns | 800 ns |

Percentiles come from JMH `SampleTime` mode; on Windows the timer resolution is about 100 ns, so
they are rounded to that.

Before the set-lookup change (commit "Match allowlist and attribute rules with a set lookup"),
the same benchmark measured 678 ns (evaluator) and 547 ns (SDK) average, 1.4 µs p99: the
allowlist was scanned linearly with a `String.valueOf` per element.

```sh
mvn -pl cohort-bench -am package -DskipTests
java -jar cohort-bench/target/benchmarks.jar
```

## Config polling (k6)

`load/config-poll.js`, 50 VUs, 30 s, 90% of requests send the current ETag.

| Metric | Value |
|---|---|
| Throughput | 23,650 req/s |
| Latency p50 / p95 | 1.5 ms / 4.5 ms |
| Errors | 0 of 716,883 |

## Event ingest (k6)

`load/events.js`, 20 VUs, 30 s, 500 events per batch (exposures plus 10% conversions).

| Metric | Value |
|---|---|
| Ingest throughput | 55,800 events/s (112 batches/s) |
| Batch latency p50 / p95 | 30 ms / 197 ms |
| Errors | 0 of 3,356 batches |

Ingest only validates and writes to Kafka. The aggregator (one consumer, set-based SQL per batch)
drained the resulting backlog at **9,250 events/s**, measured over 30 s from the
`cohort_events_processed_total` counter. Kafka absorbs the difference as consumer lag, visible on
the Grafana dashboard.

Before batching the aggregator SQL (commit "Aggregate event batches with set-based SQL"), a
100,000-user simulator run (about 110k events) took about 75 s to aggregate, roughly 1,500 events/s.

```sh
docker run --rm --network cohort_default -v "$PWD/load:/scripts" grafana/k6:0.54.0 \
  run -e BASE_URL=http://cohort-server:8080 /scripts/config-poll.js
docker run --rm --network cohort_default -v "$PWD/load:/scripts" grafana/k6:0.54.0 \
  run -e BASE_URL=http://cohort-server:8080 /scripts/events.js
```

## Experiment simulation

`cohort-simulator`, 100,000 users through the real SDK, control converting at 10% and treatment
at 11%:

| Variant | Exposures | Conversions | Rate | p-value |
|---|---|---|---|---|
| control | 49,941 | 4,956 | 9.92% | - |
| treatment | 50,059 | 5,483 | 10.95% | 1.0e-7 |

Exposure counts match the SDK's own assignment counts exactly (49,941 / 50,059), so no event was
lost or double counted end to end.
