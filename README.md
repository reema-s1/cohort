# Cohort

A feature flag and A/B experimentation service in Java: create a flag, roll it out to a percentage of users, run an experiment, and read which variant won.

## Modules

| Module | What it is |
|---|---|
| `cohort-common` | Flag model, MurmurHash3 bucketing and the rule evaluator, shared by server and SDK |
| `cohort-server` | Spring Boot service: admin API, SDK config API, event ingest, experiment results |
| `cohort-sdk` | Java client that evaluates flags locally and keeps working when the server is down |

## Running locally

```sh
docker compose up -d
mvn -pl cohort-server -am spring-boot:run
```
