# Grafana Dashboard — Temporal Payment Workflow

Open at **http://localhost:3000** → `Temporal SDK` dashboard.

Use the `namespace` variable at the top to filter by Temporal namespace (default: `All`).

---

## Sections

- [Requests](#requests)
- [Long Requests](#long-requests)
- [Workflow](#workflow)
- [Workflow Throughput](#workflow-throughput)
- [Workflow Task Processing](#workflow-task-processing)
- [Activities](#activities)
- [Activity Task Processing](#activity-task-processing)
- [Slots](#slots)
- [Sticky Cache](#sticky-cache)
- [Timeouts](#timeouts)
- [Server Health](#server-health)
- [Worker Saturation](#worker-saturation)

---

## Requests

gRPC calls from the SDK (worker + client) to the Temporal server. One workflow start, signal, or task completion = one or more RPC calls.

### RPC Requests vs Failures
Timeseries. Total SDK→server call rate vs failure rate, grouped by namespace.

- **Normal load:** requests ~5–20/s, failures = 0
- **Connectivity problem:** failure line rises, requests may drop

> **Note:** Cross-reference with **Workflow Failures By Type** — if RPC failures spike but workflow failures don't, the SDK is retrying successfully. If both spike together, workflows are dying because the server is unreachable.

---

### RPC Requests Per Operation
Timeseries. Same call rate broken down by operation: `StartWorkflowExecution`, `RespondWorkflowTaskCompleted`, `SignalWorkflowExecution`, `RecordActivityTaskHeartbeat`, etc.

- **Normal:** `RespondWorkflowTaskCompleted` and `PollWorkflowTaskQueue` dominate
- **High signal traffic:** `SignalWorkflowExecution` rises proportionally to signal rate

---

### RPC Failures Per Operation
Timeseries. Which specific operations are failing.

- **Useful for diagnosis:** a single failing operation (e.g. `SignalWorkflowExecution`) points to a specific problem, not general connectivity loss

---

### RPC Latencies (p95)
Timeseries, in milliseconds. p95 round-trip time per operation to the Temporal server.

- **Normal:** 2–15 ms
- **Degraded server:** climbs above 100 ms, workflows slow down as task completions queue up

---

## Long Requests

Long-poll operations — the worker holds a connection open waiting for work. These behave differently from regular RPCs.

### Long RPC Latencies (p95)
Timeseries, in milliseconds. p95 latency for `PollWorkflowTaskQueue` and `PollActivityTaskQueue`.

- **Expected:** constant ~30 000 ms (30 s). This is the long-poll timeout — the server holds the connection for up to 30 s waiting for a task, then returns empty. This is **normal and expected** regardless of load.
- **Not useful for alerting** on its own — see **Empty Polls** instead.

> **Note:** Do not interpret a flat 30 s line as a problem. It just means no tasks arrived during that poll window and the server returned an empty response.

---

### Long RPC Failures Per Operation
Timeseries. Failures on long-poll operations.

- **Normal:** 0
- **Non-zero:** worker lost connectivity mid-poll, or Temporal server restarted

---

## Workflow

End-to-end workflow lifecycle metrics.

### Workflow Completion
Timeseries. Rate of workflows reaching a terminal state, per workflow type.

- **During a test run:** matches the rate of `POST /payments` minus in-flight workflows
- **Example:** 3–5 completions/s for a sequential script

> **Note:** This counter counts ALL normal returns from the workflow function — including business rejections (e.g. insufficient funds, overall timeout). It does **not** distinguish business outcomes. Use **Payment Rejections / sec by Reason** for that breakdown.

---

### Workflow End-To-End Latencies
Timeseries, in milliseconds. p95 time from workflow start to completion (includes signal wait time).

- **Fast workflows (no wait):** 200–500 ms
- **Workflows waiting for a signal:** can be seconds to minutes depending on the signal delay
- **Bucket range:** 500 ms → 30 min (extended from the SDK default of 30 s)

> **Note:** If this shows a flat line at 30 000 ms, the histogram buckets were not extended correctly — all workflows that took > 30 s are clamped to the top bucket.

---

### Workflow Failures By Type
Timeseries. Rate of workflows that terminated with a Temporal-level failure (unhandled exception propagated out of the workflow function), per workflow type.

- **Normal:** 0
- **Non-zero:** a bug in workflow or activity code threw an unhandled exception. Check Temporal UI for the failure reason and stack trace.

> **Note:** Business rejections (returning `PaymentStatus.REJECTED`) do **not** appear here — they complete normally. Only uncaught exceptions that crash the workflow show here.

---

## Workflow Throughput

Operational throughput and queue depth. Useful during load tests and for capacity planning.

### Workflows Started / sec
Timeseries. Rate of `StartWorkflowExecution` calls, i.e. incoming payment requests accepted by the app.

- **Sequential script:** ~3–5/s (limited by script subprocess overhead)
- **Parallel load test:** depends on concurrency

---

### Workflows Terminal States / sec
Timeseries. Rate of each terminal outcome: `completed`, `failed`, `cancelled`, `continue_as_new`.

- **Normal:** `completed` line mirrors **Workflows Started / sec** with a delay equal to signal wait time
- **`failed` > 0:** unhandled workflow exceptions
- **`cancelled` > 0:** explicit external cancellation via Temporal API

> **Note:** Compare **Workflows Started / sec** and **Workflows Terminal States / sec** together to understand throughput balance. If started rate outpaces terminal rate for an extended period, **Workflows In Progress** will grow. If terminal rate drops to 0 while started rate is still positive, something is stalling (worker down, signal never arriving, overall timeout).

---

### Workflows In Progress
Stat panel. Current count of workflows that have started but not yet reached any terminal state. Calculated as:
`started − completed − failed − cancelled − continue_as_new`

- **During a test:** reflects how many workflows are currently waiting for a signal
- **After a test:** should return to 0 once all signals have been delivered
- **Resets on app restart** — counters are in-memory, so a restart resets this to 0 regardless of actual Temporal state. The Temporal UI is the source of truth in that case.

---

### Task Queue Backlog — payment_queue
Timeseries. Approximate number of tasks waiting in the `payment_queue` queue on the Temporal server that have not yet been picked up by a worker.

- **Normal (worker keeping up):** 0
- **Worker saturated or down:** rises proportionally to unprocessed tasks

> **Note:** This metric comes from the **Temporal server** (not the app). It requires `PROMETHEUS_ENDPOINT` to be enabled on the Temporal container. A value of 0 when workflows are running normally means the worker is consuming tasks as fast as they arrive.

> **Note:** Combine with **Empty Activity/Workflow Polls**. If the backlog is 0 AND empty polls are high, the worker has spare capacity but there is simply no work — this is fine. If the backlog is growing AND empty polls are low (or 0), the worker is fully occupied and falling behind — add worker capacity.

---

### Payment Rejections / sec by Reason
Timeseries. Rate of business rejections broken down by reason tag (e.g. `insufficient funds`, `Overall timeout reached`, `Transfer failed`).

- **Normal mixed test:** `insufficient funds` dominates (50% of payments are set to fail)
- **`Overall timeout reached` rising:** signals are arriving after the 10-minute workflow timeout — consider extending the timeout or reducing signal delay in tests

> **Note:** These are business-level outcomes counted via a custom Micrometer counter in `PaymentActivitiesImpl`. They all flow through `temporal_workflow_completed_total` from Temporal's perspective, so Temporal UI shows them as completed workflows.

---

## Workflow Task Processing

How the worker processes workflow tasks (the Temporal execution model dispatches workflow code as tasks).

### Workflow Task Throughput
Timeseries. Rate of successfully completed workflow tasks (polls that returned a task and were processed).

- **Normal:** roughly 2–3× the workflow start rate (each workflow typically goes through multiple task rounds: start → wait for signal → resume → complete)
- **Drops to 0:** worker stopped processing — check **Workflow Task Failed** and **Slots Available**

---

### Workflow Task Schedule To Start (p95)
Timeseries, in milliseconds. Time between Temporal server scheduling a workflow task and the worker picking it up.

- **Normal:** < 50 ms
- **> 200 ms:** task queue is backing up — worker is not polling fast enough or is overloaded

> **Note:** This is the most direct indicator of workflow worker saturation. A rising schedule-to-start combined with **Slots Available = 0** confirms the worker needs more concurrency or instances.

---

### Workflow Task Execution Latency (p95)
Timeseries, in milliseconds. Time the worker spends actually executing a workflow task (running workflow code).

- **Normal:** < 10 ms (workflow code should be fast and deterministic)
- **Elevated:** expensive computation in workflow code, or a large event history causing slow replay

---

### Workflow Task Replay Latency (p95)
Timeseries, in milliseconds. Time spent replaying workflow history when a sticky cache miss occurs.

- **Normal:** low or 0 (most executions hit the sticky cache)
- **Spikes:** cache miss triggered a full replay — check **Sticky Cache Miss** and **Sticky Cache Forced Eviction**

> **Note:** Replay latency spikes alongside cache misses are expected when a worker restarts (cache is cold). Sustained high replay latency indicates the event history is large or the cache is under pressure.

---

### Workflow Task Failed
Timeseries. Rate of workflow task execution failures (task attempted but failed — usually non-determinism errors or panics).

- **Normal:** 0
- **Non-zero:** a nondeterminism violation (code change broke replay), or a panic inside workflow code. Check Temporal UI → Workflow → History for `WorkflowTaskFailed` events.

---

### Workflow Task Empty Polls
Timeseries. Rate of polls that returned no task (worker asked for work, server had none).

- **Normal during idle periods:** high (worker is polling continuously, finding no work)
- **Normal during active periods:** drops as tasks become available
- **Always high even during load:** worker is polling but tasks are not being dispatched — possible queue name mismatch or wrong namespace

> **Note:** Empty polls alone are not a problem. The combination to watch: **empty polls are high** + **task queue backlog is growing** = the worker is polling the wrong queue, or there is a connectivity issue between worker and server.

---

## Activities

### Activity Throughput
Timeseries. Rate of activity executions starting (derived from `schedule_to_start` count), per namespace.

- **Normal:** roughly matches workflow throughput × number of activities per workflow (this workflow has up to 3 activities: `ReserveFunds`, `Transfer`, `Publish`)
- **Drops while workflows are running:** activity worker is not picking up tasks — check **Slots Available** and **Empty Activity Polls**

---

### Activity Execution Latencies (p95)
Timeseries, in milliseconds. p95 time the worker spends executing an activity (from task pickup to completion), per activity type.

- **`ReserveFunds`:** fast mock call, typically < 50 ms
- **`Transfer`:** external HTTP call via WireMock, typically 50–200 ms
- **`PublishCompleted` / `PublishRejected`:** external HTTP call, typically 50–200 ms
- **All zeroes:** no activities have completed yet, or the histogram has not been initialised (needs at least one completion)

---

### Activity Failed
Timeseries. Rate of activity execution failures (after all retries exhausted), per activity type.

- **Normal:** 0 (Temporal retries failed activities automatically)
- **Non-zero:** all retries exhausted — the external system is consistently failing. Check WireMock or the target service.

---

## Activity Task Processing

### Empty Activity Polls
Timeseries. Rate of activity polls that returned no task.

- **Behaviour:** same as workflow empty polls — continuously high when idle, drops when activities are being scheduled

> **Note:** Same combined read as workflow empty polls: **empty polls high + backlog growing = worker polling wrong queue**. If empty polls drop to near 0 during a test, it means the activity worker is fully occupied (no spare capacity to return empty polls).

---

### Activity Schedule To Start (p95)
Timeseries, in milliseconds. Time between Temporal scheduling an activity and the worker picking it up.

- **Normal:** < 100 ms
- **Elevated:** activity worker saturated — too many concurrent workflows scheduling activities faster than the worker can consume them

> **Note:** This is the primary indicator of activity worker saturation, equivalent to **Workflow Task Schedule To Start** for the activity side. Combine with **Slots Available** (ActivityWorker type) to confirm.

---

## Slots

Worker concurrency capacity. One slot = one concurrent workflow task or activity execution.

### Slots Available
Timeseries. Number of free slots per worker type (`WorkflowWorker`, `ActivityWorker`).

- **Normal:** close to the configured maximum (default: 200 per type)
- **Approaching 0:** worker is at full concurrency — new tasks will queue

> **Note:** Slots Available close to 0 combined with rising **Schedule To Start** latency is the definitive signal of worker saturation. Slots Available close to max with empty polls going up means the worker has excess capacity relative to current load — this is fine.

---

### Slots Used
Timeseries. Number of slots actively executing tasks.

- **Normal under load:** rises and falls with workflow/activity throughput
- **Flat at max:** worker fully saturated — matches **Slots Available = 0**

---

## Sticky Cache

The sticky cache stores in-memory workflow state (event history replay result) so that subsequent workflow tasks for the same workflow run on the same worker without replaying from scratch.

### Sticky Cache Size
Timeseries. Number of workflows currently held in the sticky cache.

- **Normal:** grows during a test run, stabilises near the configured `workflowCacheSize`
- **Drops suddenly:** worker restarted (cache is in-memory only)

---

### Sticky Cache Hit
Timeseries. Rate of workflow tasks that found their state in the sticky cache (no replay needed).

- **High hit rate (> 95%):** optimal — most tasks execute without replay overhead

---

### Sticky Cache Miss
Timeseries. Rate of workflow tasks that did not find their state in cache (full replay triggered).

- **Normal on startup:** high briefly while the cache warms up
- **Sustained high:** cache is too small for the number of concurrent workflows, or the worker is being restarted frequently. Increase `workflowCacheSize` or reduce concurrency.

> **Note:** A sticky cache miss is not fatal — the workflow replays from history and continues. The cost is latency (see **Workflow Task Replay Latency**). High miss rate + high replay latency + growing schedule-to-start latency together indicate the worker is spending most of its time replaying rather than executing new tasks.

---

### Sticky Cache Forced Eviction
Timeseries. Rate of cache entries forcibly evicted to make room for new ones.

- **Normal:** 0 or very low
- **Sustained:** cache is too small for the concurrent workflow count. Evictions cause replays on the next task for evicted workflows.

---

## Timeouts

### Task Dispatch Timeouts
Timeseries. Rate of tasks (Workflow and Activity) that were queued on the Temporal server but not picked up by a worker before the poll window expired. Broken down by `task_type` and `taskqueue`. Source: Temporal server metrics.

- **Normal:** low or occasional spikes during worker restarts
- **Sustained non-zero:** workers are not polling fast enough — tasks are queued but no worker claims them in time. Cross-reference with **Slots Available** (if 0, worker is saturated) and **Empty Polls** (if high, worker is polling but tasks are dispatched to wrong queue or namespace).

> **Note on SDK timeout metrics:** `temporal_workflow_timeout_total` and `temporal_activity_timeout_total` do not exist in the Temporal Java SDK 1.32. Execution timeouts (activity start-to-close, workflow execution timeout) surface as failures — they appear in **Workflow Failures By Type** and **Activity Failed** panels respectively.

---

## Server Health

These panels use metrics scraped directly from the Temporal server (`localhost:8000/metrics`), not from the application. They show what happens inside Temporal itself, independent of the SDK view.

### Service Request Rate
Timeseries. Rate of internal gRPC requests handled by each Temporal service component: `frontend` (client/worker-facing), `history` (workflow state machine), `matching` (task dispatch).

- **Normal:** frontend and history dominate during workflow execution
- **Matching spikes:** heavy task dispatch activity (many workflows scheduling activities)

---

### Service Errors by Type
Timeseries. Rate of server-side errors broken down by `service_name` and `error_type`.

- **`serviceerror_NotFound` on `SignalWorkflowExecution`:** signal sent to an already-completed workflow — normal and expected in tests
- **`serviceerror_Unavailable`:** server under resource pressure
- **`serviceerror_ResourceExhausted`:** rate limiting or DB connection pool exhausted

> **Note:** Cross-reference with **RPC Failures Per Operation** (SDK view). If the SDK sees failures but service errors are low, the problem is network between app and Temporal. If both spike together, the issue is inside the Temporal server.

---

### Service Latency p95
Timeseries, in milliseconds. p95 latency of gRPC calls processed by each Temporal service.

- **Normal:** frontend 2–10 ms, history 5–20 ms
- **Frontend elevated:** workflow starts and signal delivery slow — directly visible to end users
- **History elevated:** workflow task processing slows, increasing end-to-end workflow latency

---

### Persistence Latency p95
Timeseries, in milliseconds. p95 database (Postgres) operation latency per operation type.

- **Normal:** < 5 ms for reads, < 10 ms for writes
- **Elevated:** DB is the bottleneck — Temporal is heavily write-intensive (every workflow state change writes to DB). This is the most common production bottleneck in high-throughput Temporal deployments.

> **Note:** This is the deepest visibility into Temporal's DB dependency. If **Service Latency** is elevated but **Persistence Latency** is normal, the bottleneck is in Temporal's internal logic. If persistence latency is elevated, DB capacity needs to be increased.

---

### Persistence Errors
Timeseries. Rate of database errors by operation and error type.

- **`serviceerror_NotFound` on `GetTaskQueue`:** normal on startup (task queue not yet initialised)
- **Errors on `CreateWorkflowExecution` or `UpdateWorkflowExecution`:** DB write failures — workflows may be lost or stuck

---

## Worker Saturation

### Worker Saturation — Schedule-to-Start / Slots / Backlog
Timeseries, combined. Three saturation signals on one panel.

- **Left axis (ms):** workflow and activity schedule-to-start p95 — time tasks wait before a worker picks them up
- **Right axis (count, dashed):** workflow slots available (green), activity slots available (blue), task queue backlog (red)

**Saturation pattern to watch:**
- schedule-to-start **rises** + slots available **drops toward 0** + backlog **grows** → worker overwhelmed, add capacity
- schedule-to-start **rises** but slots available **remains high** → bottleneck is not in the worker — check **Service Latency** (server may be slow dispatching tasks)
- backlog **grows** but schedule-to-start **stays low** → backlog is accumulating faster than it's being measured; worker likely just went offline

> **Note:** This panel consolidates signals from the **Slots**, **Workflow Task Processing**, **Activity Task Processing**, and **Task Queue Backlog** panels into a single saturation view. Use it as the first panel to check when investigating worker performance problems.
