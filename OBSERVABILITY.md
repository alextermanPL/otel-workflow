# Observability Setup

Full distributed tracing (Jaeger) and metrics (Prometheus) for the Temporal payment workflow.

---

## 1. Dependencies Added

All added to `build.gradle.kts`. Both were already managed by the Quarkus BOM — no versions needed.

| Dependency | Why |
|---|---|
| `io.quarkus:quarkus-opentelemetry` | OTel SDK + OTLP exporter. Auto-instruments JAX-RS endpoints and REST clients. Also auto-wires Temporal tracing when `quarkus-temporal` is detected. |
| `io.quarkus:quarkus-micrometer-registry-prometheus` | Exposes Micrometer metrics (JVM, HTTP, Temporal SDK) at `/q/metrics` in Prometheus scrape format. |

> **No manual interceptors or CDI beans needed.** `quarkus-temporal` automatically enables OTel tracing via `quarkus.temporal.telemetry.enabled=true` (default) when `quarkus-opentelemetry` is on the classpath.

---

## 2. Application Configuration

Added to `src/main/resources/application.properties`:

```properties
# OpenTelemetry — send traces to OTel Collector via OTLP gRPC
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.exporter.otlp.protocol=grpc
quarkus.application.name=payment-workflow
quarkus.otel.resource.attributes=service.version=1.0.0
quarkus.otel.propagators=tracecontext,baggage
```

---

## 3. Local Testing Infrastructure (docker-compose.yml)

Three services added to the existing `docker-compose.yml`.

### Jaeger (trace storage + UI)

```yaml
jaeger:
  image: jaegertracing/all-in-one:1.62.0
  container_name: jaeger
  environment:
    - COLLECTOR_OTLP_ENABLED=true
  ports:
    - "16686:16686"   # UI → http://localhost:16686
    - "14268:14268"   # HTTP collector (legacy)
```

### OTel Collector (receives traces from app, forwards to Jaeger + exposes metrics)

```yaml
otel-collector:
  image: otel/opentelemetry-collector-contrib:0.148.0
  container_name: otel-collector
  command: ["--config=/etc/otel-collector-config.yaml"]
  volumes:
    - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
  ports:
    - "4317:4317"   # OTLP gRPC — app sends traces here
    - "4318:4318"   # OTLP HTTP
    - "8889:8889"   # Prometheus scrape endpoint
  depends_on:
    - jaeger
```

Collector config (`otel-collector-config.yaml`): receives OTLP on 4317/4318, batches, forwards traces to Jaeger and exposes metrics for Prometheus.

### Prometheus (metrics storage + UI)

```yaml
prometheus:
  image: prom/prometheus:v3.1.0
  container_name: prometheus
  volumes:
    - ./prometheus.yml:/etc/prometheus/prometheus.yml
  ports:
    - "9091:9090"   # UI → http://localhost:9091
  depends_on:
    - otel-collector
```

Prometheus config (`prometheus.yml`) scrapes two targets:
- `host.docker.internal:9090/q/metrics` — Micrometer metrics directly from the app (Temporal SDK + JVM + HTTP)
- `otel-collector:8889` — OTel collector self-metrics

### Starting the stack

```bash
podman-compose up -d
./gradlew quarkusDev
```

### Clean restart from scratch

```bash
podman-compose down -v          # removes containers AND volumes (wipes Temporal DB + Prometheus data)
podman-compose up -d
# also restart the Quarkus app to reset in-memory metric counters
```

> **Note (ARM64 / Apple Silicon):** `otel/opentelemetry-collector-contrib:0.116.0` has no ARM64 build. Use `0.148.0` or later.

> **Note (Podman clock drift):** If Prometheus shows "server time out of sync", run:
> ```bash
> podman machine ssh sudo hwclock --hctosys
> ```

---

## 4. Traces

### How it works

Every incoming REST call creates a root span. `quarkus-temporal` propagates the OTel context into Temporal's workflow headers, creating child spans for every workflow task and activity execution. Outbound REST client calls (`@RestClient`) are auto-instrumented with W3C `traceparent` headers.

One payment flow produces **two separate traces**:

| Trace | Root span | Spans |
|---|---|---|
| Workflow trace | `POST /payments` | `StartWorkflow` → `RunWorkflow` → per activity: `StartActivity` + `RunActivity` + REST client call |
| Signal trace | `POST /payments/{id}/reservation-result` | `SignalWorkflow` + `HandleSignal` |

Both traces share the `workflowId` tag, allowing correlation in Jaeger.

### Jaeger UI

Open **http://localhost:16686**, select service `payment-workflow`, click **Find Traces**.

To filter by workflow ID, use the **Tags** field: `workflowId=payment:1`

### Jaeger API — find all traces by workflowId

```bash
# All traces for a specific workflow (URL-encoded {"workflowId":"payment:1"})
curl "http://localhost:16686/api/traces?service=payment-workflow&tags=%7B%22workflowId%22%3A%22payment%3A1%22%7D&limit=20"
```

### Export activity durations to CSV

```bash
./scripts/run.sh                          # outputs report.csv
./scripts/run.sh --out /tmp/export.csv    # custom path
./scripts/run.sh --limit 500              # fetch more traces
```

Output columns: `workflowId`, `runId`, `status`, `start_timestamp`, `end_timestamp`, `total_ms`, `start_api_ms`, `signal_api_ms`, and one `<ActivityName>_ms` column per activity type.

- `status` is inferred from which terminal activity ran: `COMPLETED` (PublishCompleted), `REJECTED` (PublishRejected), `IN_FLIGHT` (neither yet)
- `total_ms = end_timestamp - start_timestamp` — includes signal wait time, reflects real end-to-end duration

---

## 5. Metrics

### Prometheus UI

Open **http://localhost:9091**, paste queries into the **Graph** tab.

### Workflow execution counts

| What | Query |
|---|---|
| Started | `sum(temporal_request_total{operation="StartWorkflowExecution"})` |
| Completed | `sum(temporal_workflow_completed_total or vector(0))` |
| Failed | `sum(temporal_workflow_failed_total or vector(0))` |
| Ongoing (in-flight) | `sum(temporal_request_total{operation="StartWorkflowExecution"}) - sum(temporal_workflow_completed_total or vector(0)) - sum(temporal_workflow_failed_total or vector(0))` |

> `or vector(0)` is needed because `completed` and `failed` counters only appear after the first event. Without it, the in-flight query returns no data on a fresh start.

### Activity metrics

```promql
# Executions per activity type
temporal_activity_execution_latency_seconds_count

# p99 execution latency per activity
histogram_quantile(0.99, rate(temporal_activity_execution_latency_seconds_bucket[5m]))

# Filter by specific activity
temporal_activity_execution_latency_seconds_count{activity_type="ReserveFunds"}
```

---

## 6. Production Metrics Reference

### Worker health (alert immediately)

| Metric | Alert condition | What it means |
|---|---|---|
| `temporal_worker_task_slots_available` | == 0 | Worker saturated — tasks queuing or timing out |
| `temporal_num_pollers` | Drops to 0 | Worker stopped polling — no tasks will be picked up |
| `temporal_workflow_active_thread_count` | Unbounded growth | Thread leak or cache misconfiguration |

### Workflow execution

| Metric | Alert condition | What it means |
|---|---|---|
| `temporal_workflow_task_schedule_to_start_latency_seconds` | p95 > 1s | Workflow tasks backlogged — add worker capacity |
| `temporal_workflow_task_execution_latency_seconds` | p95 > 1s | Worker CPU-bound or non-deterministic code is slow |
| `temporal_workflow_task_replay_latency_seconds` | Spikes | Large event history or heavy payload causing slow replay |
| `temporal_workflow_endtoend_latency_seconds` | Exceeds SLA | Total workflow duration above acceptable threshold |
| `temporal_workflow_failed_total` | Sustained increase | Workflows terminating with errors |
| `temporal_workflow_task_execution_total` with `failure_reason="nondeterminism"` | Any | Broken determinism contract — code change broke replay |

### Activity execution

| Metric | Alert condition | What it means |
|---|---|---|
| `temporal_activity_schedule_to_start_latency_seconds` | p95 > 1s | Activity worker saturated — tasks queuing |
| `temporal_activity_execution_latency_seconds` | Elevated | Slow external calls or underprovisioned workers |
| `temporal_activity_succeed_endtoend_latency_seconds` | Exceeds SLA | Total activity time (schedule → complete) above budget |
| `temporal_activity_execution_failed_total` | Sustained increase | Activities failing beyond retry budget |

### Sticky cache (workflow state cache)

| Metric | Target | What it means |
|---|---|---|
| `temporal_sticky_cache_hit_total / (hit + miss)` | > 95% | Cache hit rate — misses cause full replay from event history |
| `temporal_sticky_cache_total_forced_eviction_total` | Stable / zero | Evictions due to cache pressure — increase `workflowCacheSize` or add workers |
| `temporal_sticky_cache_size` | Below `WorkflowCacheSize` | Number of workflows currently cached in memory |

### gRPC client

| Metric | Alert condition | What it means |
|---|---|---|
| `temporal_request_failure_total` | Sustained increase | SDK cannot reach Temporal server |
| `temporal_long_request_failure_total` | Any | Workers cannot poll for tasks — check connectivity |

### Key PromQL expressions for prod dashboards

```promql
# Worker slot utilisation (0-100%)
100 * temporal_worker_task_slots_used / (temporal_worker_task_slots_used + temporal_worker_task_slots_available)

# Sticky cache hit rate (%)
100 * rate(temporal_sticky_cache_hit_total[5m]) / (rate(temporal_sticky_cache_hit_total[5m]) + rate(temporal_sticky_cache_miss_total[5m]))

# Activity p99 schedule-to-start (backlog indicator)
histogram_quantile(0.99, rate(temporal_activity_schedule_to_start_latency_seconds_bucket[5m]))

# Workflow p99 end-to-end latency
histogram_quantile(0.99, rate(temporal_workflow_endtoend_latency_seconds_bucket[5m]))
```
