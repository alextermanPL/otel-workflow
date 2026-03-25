# Temporal Payment Workflow — OTel Example

A Quarkus + Kotlin example of a payment workflow built on Temporal with full observability:
distributed tracing (Jaeger) and metrics (Prometheus + Grafana).

---

## Running locally

**Prerequisites:** Podman, podman-compose, Java 21, Git.

```bash
# 1. Start the infrastructure
podman-compose up -d

# 2. Start the application
./gradlew quarkusDev
```

The app starts on **http://localhost:9090**.

### Clean restart (wipes all data)

```bash
podman-compose down -v   # removes containers and volumes
podman-compose up -d
# restart the app too — in-memory metric counters reset with the JVM
```

---

## Observability UIs

| UI | URL | What it shows |
|---|---|---|
| Grafana | http://localhost:3000 | Metrics dashboard (`Temporal SDK` dashboard) |
| Jaeger | http://localhost:16686 | Distributed traces |
| Prometheus | http://localhost:9091 | Raw metrics, ad-hoc queries |
| Temporal UI | http://localhost:8080 | Workflow execution history |
| App metrics | http://localhost:9090/q/metrics | Raw Prometheus scrape endpoint |

---

## Traffic scripts

All scripts are in `scripts/`. Run from the project root.

### `success_payment.sh` — sequential successful payments

Starts payments one by one and immediately sends a success signal for each.

```bash
./scripts/success_payment.sh [start] [end]

./scripts/success_payment.sh 1       # single payment (ID=1)
./scripts/success_payment.sh 1 10    # payments 1 through 10
```

---

### `un_success_payment.sh` — sequential failing payments

Starts payments and immediately sends a failure signal with a configurable rejection reason.

```bash
./scripts/un_success_payment.sh [start] [end] [reason]

./scripts/un_success_payment.sh 1 5                      # IDs 1–5, reason: "insufficient funds"
./scripts/un_success_payment.sh 6 6 "account frozen"     # ID 6, custom reason
```

---

### `mixed_payments.sh` — realistic mixed load

Starts a batch of payments with randomly chosen outcomes (50% success / 50% fail) and sends
each signal after a **log-uniform random delay between 80 ms and 25 minutes**. Signal delivery
runs in the background so all workflow starts fire first, then signals trickle in naturally.

This is the most realistic script — it generates long-running in-progress workflows, tests the
10-minute overall timeout, and produces varied E2E latency distributions visible in Grafana.

```bash
./scripts/mixed_payments.sh [start] [end]

./scripts/mixed_payments.sh 1 20     # 20 payments with random delays
./scripts/mixed_payments.sh 21 40    # next batch
```

Stop with `Ctrl+C` — kills all background signal jobs cleanly.

> **Throughput:** the script is sequential (~6 req/s). To generate higher load run multiple
> instances in parallel with non-overlapping ID ranges.

---

### `run.sh` — export traces to CSV

Fetches traces from Jaeger and exports per-workflow activity durations to a CSV file.

```bash
./scripts/run.sh                        # outputs scripts/report.csv
./scripts/run.sh --out /tmp/export.csv  # custom output path
./scripts/run.sh --limit 5000           # fetch more traces (default is low)
```

CSV columns: `workflowId`, `runId`, `status`, `start_timestamp`, `end_timestamp`,
`total_ms`, `start_api_ms`, `signal_api_ms`, `ReserveFunds_ms`, `Transfer_ms`,
`PublishCompleted_ms` / `PublishRejected_ms`.

---

## Traces

Every payment produces **two separate traces** in Jaeger:

| Trace | Triggered by | Spans |
|---|---|---|
| Workflow trace | `POST /payments` | StartWorkflow → RunWorkflow → activities + REST calls |
| Signal trace | `POST /payments/{id}/reservation-result` | SignalWorkflow → HandleSignal |

Both traces carry the `workflowId` tag for correlation.

**Finding a trace in Jaeger:**
1. Open http://localhost:16686
2. Select service `payment-workflow`, click **Find Traces**
3. To filter by workflow: use the **Tags** field → `workflowId=payment:1`

---

## Dashboard

Open the **Temporal SDK** dashboard in Grafana (http://localhost:3000).

Use the `namespace` variable at the top to filter by Temporal namespace.

See [DASHBOARD.md](DASHBOARD.md) for a description of every panel, example values,
and notes on reading panels together to diagnose problems.
