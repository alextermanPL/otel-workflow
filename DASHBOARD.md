# Grafana Dashboard — Temporal Payment Workflow

Open at **http://localhost:3000** → `Temporal SDK` dashboard.

Use the `namespace` variable at the top to filter by Temporal namespace (default: `All`).

Panels marked **[Custom]** use metrics that are manually instrumented in application code. Standard library metrics (JVM, Temporal SDK, Kafka client) are available in any app using those libraries — only the label values (task queue name, client ID, topic) need to match your configuration.

> The condensed version of each section below also appears as the **ⓘ tooltip** on the corresponding Grafana panel.

---

## CPU / Memory

### CPU Usage
**Metrics:** `process_cpu_usage`, `system_cpu_usage`

**What it measures:** `process_cpu_usage` is the fraction of CPU time consumed by the JVM process (0–1 scale). `system_cpu_usage` is total host CPU across all cores.

**Normal:** Process CPU tracks workload linearly and stays well below 1.0 under steady load. System CPU leaves headroom.

**Problem:**
- Process CPU near 1.0 → JVM is CPU-bound. Workflow task replay is CPU-intensive; if you see this alongside elevated *Workflow Task Execution Latency*, the worker is spending all its time replaying history.
- System CPU near 1.0 but process CPU low → another process is stealing cycles (DB, broker). Check the host.
- CPU low but latency high → bottleneck is I/O or thread contention, not compute. Check *JVM Thread States*.

**See also:** Memory Usage, Workflow Task Execution Latency, JVM Thread States (stacked).

---

### Memory Usage
**Metrics:** `jvm_memory_used_bytes`, `jvm_memory_max_bytes`

**What it measures:** JVM heap (objects, caches, workflow sticky state) used vs configured maximum, and non-heap (Metaspace, CodeCache).

**Normal:** Heap fluctuates with GC cycles but stays well below heap max. Non-heap is roughly flat after startup.

**Problem:**
- Heap approaches max → GC pressure, causing latency spikes in *Workflow Task Execution Latency* and *Activity Execution Latency*.
- Heap grows monotonically without GC drops → memory leak. Candidates: accumulated Temporal sticky cache state (see *Sticky Cache Size*), unbounded application caches, Kafka producer/consumer buffers.
- Non-heap growing unboundedly → class-loading leak (unusual in steady state).

**Note:** JVM thread stacks live off-heap (~1 MB per thread). A large *JVM Thread States (waiting)* count contributes significantly to off-heap memory outside of what this panel shows.

**See also:** CPU Usage, Sticky Cache Size, JVM Thread States (stacked).

---

## Requests (RPC)

### RPC Requests vs Failures
**Metrics:** `temporal_request_total`, `temporal_request_failure_total`

**What it measures:** Total rate of short gRPC calls from the SDK to the Temporal server (StartWorkflowExecution, SignalWorkflowExecution, etc.) and how many fail, per namespace.

**Normal:** Failure rate is 0 or negligible. Request rate tracks your workflow and signal throughput. Under normal load: ~5–20 req/s, failures = 0.

**Problem:**
- Any failure rate → check *RPC Failures Per Operation* to pinpoint which call is failing.
- Failures on `StartWorkflowExecution` → server rejecting new workflows (RPS limit, resource exhaustion). Check *Service Errors by Type*.
- Failures on `SignalWorkflowExecution` with `NotFound` → signals arriving after workflow completed (normal in some designs). Other error types are not normal.

**See also:** RPC Failures Per Operation, Service Errors by Type, Service Request Rate.

---

### RPC Failures Per Operation
**Metric:** `temporal_request_failure_total` by operation

**What it measures:** Same as above, split by individual gRPC operation. Lets you pinpoint which call is failing.

**Normal:** All series flat at 0.

**Problem:**
- `PollWorkflowTaskQueue` / `PollActivityTaskQueue` failures → worker cannot reach server (network or auth issue).
- `GetWorkflowExecutionHistory` failures → replay is failing, likely a server-side problem.
- Any operation correlating with *Service Errors by Type ResourceExhausted* → server RPS limit hit.

**See also:** RPC Requests vs Failures, Service Errors by Type.

---

### RPC Requests Per Operation
**Metric:** `temporal_request_total` by operation

**What it measures:** Request rate broken down by operation. Shows the composition of SDK-to-server traffic.

**Normal:** `PollWorkflowTaskQueue` and `PollActivityTaskQueue` dominate (long-poll). `StartWorkflowExecution` tracks your workflow start rate.

**Problem:**
- `GetWorkflowExecutionHistory` rate is high → workers replaying from scratch frequently. Check *Sticky Cache Miss* and *Sticky Cache Forced Eviction*.
- `SignalWorkflowExecution` unexpectedly high → check signal fan-out logic in application code.

**See also:** Sticky Cache Miss, Sticky Cache Forced Eviction.

---

### RPC Latencies (p95)
**Metric:** `temporal_request_latency_seconds_bucket` by operation

**What it measures:** p95 round-trip latency of short gRPC calls from the SDK to the Temporal server, in milliseconds.

**Normal:** Below 50ms for most operations in a local or dev setup (2–15ms typical). Below 10ms for polls that return immediately with a task.

**Problem:**
- Rising on `StartWorkflowExecution` or `SignalWorkflowExecution` → Temporal frontend is slow. Check *Service Latency p95* and *Persistence Latency p95*.
- Correlated spikes → GC pauses. Check *Memory Usage*.

**See also:** Service Latency p95, Persistence Latency p95, Memory Usage.

---

### Long RPC Failures Per Operation
**Metric:** `temporal_long_request_failure_total` by operation

**What it measures:** Failure rate of long-poll gRPC calls (PollWorkflowTaskQueue, PollActivityTaskQueue) — the core operations that deliver tasks to workers.

**Normal:** 0.

**Problem:**
- Any failures → workers cannot sustain a poll connection to the server. Network issue or server-side rejection.

**See also:** Long RPC Latencies, Slots Available.

---

### Long RPC Latencies (p95)
**Metric:** `temporal_long_request_latency_seconds_bucket` by operation

**What it measures:** p95 latency of long-poll operations, in milliseconds. These polls intentionally wait up to 20–30 seconds for a task before returning empty.

**Normal:** Near 20,000–30,000ms when workers are idle — that is the poll timeout, not a problem. Latency drops sharply when tasks are continuously available.

**Problem:**
- Consistently much lower than the poll timeout AND *Workflow Task Empty Polls* near 0 → workers are always getting tasks (high load). Check *Slots Available* and *Task Queue Backlog*.
- Failures > 0 → workers cannot complete a poll. Network or server issue.

**See also:** Workflow Task Empty Polls, Empty Activity Polls, Slots Available.

---

## Workflow Lifecycle

### Workflow Completion / sec
**Metric:** `temporal_workflow_completed_total` by workflow_type

**What it measures:** Rate of workflows reaching a successful terminal state, per workflow type.

> **Note:** This counts ALL normal workflow function returns — including business rejections (e.g. insufficient funds, timeout). It does not distinguish business outcomes. Use *Payment Rejections / sec by Reason* for that breakdown.

**Normal:** Tracks *Workflows Started/sec* with a lag equal to average workflow duration. During a test: 3–5 completions/s for a sequential script.

**Problem:**
- Start rate >> completion rate sustained → backlog is growing. Check *Workflows In Progress*, *Task Queue Backlog*, *Slots Available*.
- Completion rate drops to near 0 while started rate is non-zero → something is blocking all workflows. Check *Activity Failed*, *Workflow Failures By Type*, *JVM Thread States*.

**See also:** Workflows Started/sec, Workflows Terminal States/sec, Workflows In Progress, Task Queue Backlog.

---

### Workflow End-to-End Latency (p95)
**Metric:** `temporal_workflow_endtoend_latency_seconds_bucket` by workflow_type

**What it measures:** p95 time from workflow start to terminal state, in milliseconds.

**Normal:** Depends on workflow design. Fast workflows (no wait): 200–500ms. Workflows waiting for external signals: seconds to minutes, depending on signal delay — this is expected by design.

**Problem:**
- Latency growing over time under the same load → degradation. Compare with *JVM Thread States* (thread accumulation) and *Activity Execution Latency* (slow activities).
- Sudden jump → check *Activity Failed* (retries add latency), *Workflow Failures By Type*, *RPC Latencies*.

**See also:** Activity Execution Latency, JVM Thread States, Workflows In Progress.

---

### Workflow Failures By Type
**Metric:** `temporal_workflow_failed_total` by workflow_type

**What it measures:** Rate of workflows reaching a failed terminal state — unhandled exceptions that propagated out of the workflow function. Business rejections (returning a result) do NOT appear here.

**Normal:** 0 or very low.

**Problem:**
- Sustained failure rate → unhandled exceptions in workflow or activity code. Check Temporal UI → Workflow → History for the failure reason and stack trace.
- Spike after deployment → new code is incompatible with existing histories (non-determinism). Use `Workflow.getVersion()` for safe history migration.

**See also:** Activity Failed, Workflow Task Failed, RPC Failures Per Operation.

---

### Workflows Started / sec
**Metric:** `temporal_request_total{operation="StartWorkflowExecution"}` by namespace, workflow_type

**What it measures:** Rate of new workflow executions being started — your inbound traffic rate.

**Normal:** Sequential script: ~3–5/s. Parallel load test: depends on concurrency.

**Problem:**
- Rate is 0 when traffic is expected → check *RPC Requests vs Failures* (start calls failing?).
- Rate far exceeds *Workflow Completion/sec* → backlog accumulating. Check *Workflows In Progress*.

**See also:** Workflow Completion/sec, Workflows In Progress, RPC Requests vs Failures.

---

### Workflows Terminal States / sec
**Metrics:** `temporal_workflow_completed_total`, `temporal_workflow_failed_total`, `temporal_workflow_cancelled_total`, `temporal_workflow_continue_as_new_total`

**What it measures:** All terminal outcomes in one panel — the total drain rate of the workflow system.

**Normal:** `completed` mirrors *Workflows Started/sec* with a delay equal to signal wait time. `failed` and `cancelled` near 0.

**Problem:**
- All terminal rates near 0 while started rate is non-zero → total blockage. Check *Slots Available*, *Task Queue Backlog*, *JVM Thread States*.
- `failed` growing → check *Workflow Failures By Type*.

> **Note:** Compare started vs terminal rates together to understand throughput balance. If terminal rate drops to 0 while started rate is positive, something is stalling (worker down, signal never arriving, overall timeout).

**See also:** Workflows Started/sec, Task Queue Backlog, Slots Available, JVM Thread States.

---

### Workflows In Progress
**Metric:** cumulative math — started minus all terminal states

**What it measures:** Estimated count of workflow executions currently in a non-terminal state. Resets to 0 on worker restart (counters are in-memory).

**Normal:** Reflects how many workflows are waiting for a signal. Should return to 0 once all signals are delivered after a test.

**Problem:**
- Growing unboundedly → drain rate < start rate. Check *Workflow Completion/sec* vs *Workflows Started/sec*.
- Never drops to 0 after traffic stops → workflows are stuck (signal never arrives, stuck activity, timeout not configured). Check *Task Queue Backlog* and *JVM Thread States*.

**Java SDK note:** Each in-progress workflow in the sticky cache holds at least one real JVM thread in WAITING state. A large in-progress count directly explains elevated *JVM Thread States (waiting)*. This is by design — not a bug — but it sets a hard limit on concurrent workflows per worker (`maxWorkflowThreadCount`, default 600).

**See also:** JVM Thread States, Sticky Cache Size, Task Queue Backlog, Workflows Started/sec.

---

### Payment Rejections / sec by Reason **[Custom]**
**Metric:** `payment_rejected_total` by reason — *replace with your application rejection counter*

**What it measures:** Rate of business-level rejections broken down by reason label (e.g. `insufficient funds`, `Overall timeout reached`, `Transfer failed`).

**Normal:** Baseline rejection rate is business-defined. In a mixed test with 50% failures: `insufficient funds` dominates. `Overall timeout reached` rising = signals arriving after the 10-minute workflow timeout.

**Problem:**
- One reason spiking → systematic failure on that path.
- All reasons spiking together → systemic failure. Check *Workflow Failures By Type* and *Activity Failed*.

> **Note:** These are business-level outcomes counted via a custom Micrometer counter. From Temporal's perspective they all show as `temporal_workflow_completed_total`.

**See also:** Activity Execution Latency, Workflow Failures By Type, Rejected Payments — Total by Reason.

---

### Task Queue Backlog
**Metric:** `approximate_backlog_count{taskqueue="payment_queue"}` — *replace `payment_queue` with your task queue name*

**What it measures:** Approximate count of tasks queued on the Temporal server not yet picked up by a worker, by task type (Workflow / Activity). Reported by the Temporal server.

**Normal:** 0 or near 0 — tasks should be dispatched almost immediately when workers have capacity.

**Problem:**
- Growing + *Slots Available* dropping → workers at capacity.
- Growing + *Slots Available* still high → poll routing or sticky cache issue. Check *Sticky Cache Forced Eviction*.
- Growing + *Workflow Task Empty Polls* / *Empty Activity Polls* dropping to 0 → workers stopped polling.

> **Note:** Backlog = 0 AND empty polls high = worker has spare capacity but no work — this is fine. Backlog growing AND empty polls low = worker is fully occupied and falling behind.

**See also:** Slots Available, Workflow Task Empty Polls, Empty Activity Polls, Worker Saturation panel.

---

## Workflow Tasks

### Workflow Task Throughput
**Metric:** `temporal_workflow_task_queue_poll_succeed_total` by task_queue

**What it measures:** Rate of workflow tasks successfully polled and executed. Each workflow event (signal received, activity completed, timer fired) generates one task. Typically 2–3× the workflow start rate since each workflow goes through multiple task rounds.

**Problem:**
- Drops to 0 while workflows are active → workers stopped polling. Check *Workflow Task Empty Polls* (if also 0, workers are not running).

**See also:** Workflow Task Schedule To Start, Workflow Task Empty Polls, Slots Available.

---

### Workflow Task Schedule To Start (p95)
**Metric:** `temporal_workflow_task_schedule_to_start_latency_seconds_bucket`

**What it measures:** p95 time between when the server queues a workflow task and when a worker picks it up, in milliseconds. Pure dispatch latency — before the workflow code runs.

**Normal:** Below 50ms. Sticky execution keeps this near-instant because the task goes directly to the worker that has the workflow cached.

**Problem:**
- Rising + *Slots Available (WorkflowWorker)* still high → server is the bottleneck. Check *Service Latency p95* and *Persistence Latency p95*.
- Rising + *Slots Available* dropping to 0 → worker is saturated. Check *Workflows In Progress* and *JVM Thread States* — in the Java SDK, each in-progress workflow consumes a thread slot.
- Rising + *Sticky Cache Forced Eviction* rising → evictions are routing tasks to the normal queue, bypassing the sticky fast-path.

**See also:** Slots Available, Sticky Cache Forced Eviction, Service Latency p95, Worker Saturation.

---

### Workflow Task Failed
**Metric:** `temporal_workflow_task_execution_failed_total` by workflow_type

**What it measures:** Rate of workflow tasks that failed during execution. Typically indicates non-determinism bugs in workflow code (e.g., `Math.random()`, `System.currentTimeMillis()`, or non-deterministic branching on replay).

**Normal:** 0. Any non-zero value is serious.

**Problem:**
- Any failures → non-determinism in workflow code. Temporal retries the task but repeated failures stall the workflow. Check app logs for `NonDeterministicException`.
- Spike after deployment → new code is incompatible with existing histories. Use `Workflow.getVersion()` for safe migration.

**See also:** Workflow Failures By Type, Workflow Task Replay Latency.

---

### Workflow Task Execution Latency (p95)
**Metric:** `temporal_workflow_task_execution_latency_seconds_bucket` by workflow_type

**What it measures:** p95 time to run the workflow code from a task start to the next blocking point (`await()`, activity schedule, or timer), in milliseconds.

**Normal:** Below 10–50ms. Workflow code should be fast and deterministic — only advancing state to the next step.

**Problem:**
- High → workflow code doing heavy computation or blocking I/O. Never use `Thread.sleep()`, blocking HTTP calls, or large data processing in workflow code.
- Correlated with *CPU Usage* spikes → GC pauses or CPU saturation.

**See also:** Workflow Task Replay Latency, CPU Usage, Workflow Task Failed.

---

### Workflow Task Replay Latency (p95)
**Metric:** `temporal_workflow_task_replay_latency_seconds_bucket` by workflow_type

**What it measures:** p95 time to replay a workflow's full history from scratch when not in the sticky cache, in milliseconds. Must deterministically re-execute all past events.

**Normal:** Low or 0 when most tasks hit the cache. Proportional to history size otherwise. Spikes expected after worker restart (cold cache).

**Problem:**
- Growing over time → history length growing (approaching the 50,000 event limit). Use `continue_as_new` to truncate.
- High replay AND *Sticky Cache Miss* rising → workers replaying too often. Increase `workflowCacheSize`.

**See also:** Sticky Cache Miss, Sticky Cache Forced Eviction, Workflow Task Execution Latency.

---

### Workflow Task Empty Polls
**Metric:** `temporal_workflow_task_queue_poll_empty_total` by task_queue

**What it measures:** Rate of long-poll requests for workflow tasks that returned empty (poll window expired, no task). Indicates workers are alive and polling but the task queue is quiet.

**Normal:** **High empty poll rate is healthy** when the system is idle or under low load — workers are ready and waiting.

**Problem:**
- Empty polls drop to 0 AND *Task Queue Backlog* is non-zero → tasks are queued but no worker is picking them up. Possible: Slots Available at 0, or wrong queue name.
- Always high even during load AND backlog growing → worker is polling the wrong queue, or there is a routing issue.

**See also:** Task Queue Backlog, Slots Available, Workflow Task Throughput.

---

## Activities

### Activity Throughput
**Metric:** `temporal_activity_schedule_to_start_latency_seconds_count` by namespace

**What it measures:** Rate of activities successfully dispatched to and started by workers. Roughly matches workflow throughput × number of activities per workflow step (this workflow has up to 4: `ReserveFunds`, `SendFraudCheck`, `Transfer`, `Publish`).

**Problem:**
- Drops to 0 while workflows are active → no activities dispatched. Check *Slots Available (ActivityWorker)* and *Empty Activity Polls*.

**See also:** Slots Available, Empty Activity Polls, Activity Schedule To Start.

---

### Activity Failed
**Metric:** `temporal_activity_execution_failed_total` by activity_type

**What it measures:** Rate of activity executions that threw an exception, per activity type. Temporal retries per the configured retry policy.

**Normal:** 0 or low. Transient failures followed by successful retries are acceptable but should be monitored.

**Problem:**
- Sustained failures on one activity type → that activity's dependency is down (external API, database, Kafka broker).
- All activity types failing → systemic issue (network, credentials, resource exhaustion).
- Failures exhaust retries → *Workflow Failures By Type* will spike.

**See also:** Workflow Failures By Type, Activity Execution Latency, Kafka Producer: Message Rate.

---

### Activity Execution Latency (p95)
**Metric:** `temporal_activity_execution_latency_seconds_bucket` by activity_type

**What it measures:** p95 time from when an activity starts executing until it returns, per activity type, in milliseconds.

**Normal:** `ReserveFunds`: fast REST mock, < 50ms. `Transfer` / `Publish`: external HTTP via WireMock, 50–200ms.

**Problem:**
- Growing latency on one activity type → that activity's dependency is slowing down.
- High latency AND *JVM Thread States (waiting)* growing → activities are blocking JVM threads while waiting for I/O. Each concurrent activity holds one thread for its entire duration.
- p95 >> p50 → occasional slow outliers (GC pause, downstream timeout, retry).

**See also:** JVM Thread States, Activity Failed, Kafka Producer: Latency.

---

### Empty Activity Polls
**Metric:** `temporal_activity_poll_no_task_total` by task_queue

**What it measures:** Rate of long-poll requests for activity tasks that returned empty. Indicates activity workers are alive and polling but the queue is quiet.

**Normal:** **High empty poll rate is healthy** when the system is idle — workers are ready and waiting.

**Problem:**
- Empty polls drop to 0 AND *Task Queue Backlog (Activity)* is growing → activity workers not picking up tasks. Check *Slots Available (ActivityWorker)*.
- Empty polls drop to near 0 during a test → activity worker fully occupied, no spare capacity.

**See also:** Task Queue Backlog, Slots Available, Activity Throughput.

---

### Activity Schedule To Start (p95)
**Metric:** `temporal_activity_schedule_to_start_latency_seconds_bucket` by activity_type

**What it measures:** p95 time from when Temporal schedules an activity to a worker picking it up, in milliseconds. Pure queueing time — the activity has not started yet.

**Normal:** Below 100ms when workers have free slots.

**Problem:**
- Rising + *Slots Available (ActivityWorker)* dropping to 0 → activity workers saturated. Activities are slow, holding slots longer. Check *Activity Execution Latency*.
- Rising + *Slots Available* still high → dispatch delay is server-side. Check *Service Latency p95*.
- Rising + *Empty Activity Polls* dropping to 0 → all poll threads are busy, no capacity for new tasks.

**See also:** Slots Available, Activity Execution Latency, Service Latency p95, Worker Saturation.

---

## Worker Capacity

### Slots Available
**Metric:** `temporal_worker_task_slots_available` by worker_type

**What it measures:** Number of free task execution slots per worker type (WorkflowWorker and ActivityWorker). A slot is consumed when a task starts and freed when it completes. Default maximum: 200 per type.

**Normal:** Stays well above 0.

**Problem:**
- Approaches 0 for ActivityWorker → activity workers saturated. Activities queue up.
- Approaches 0 for WorkflowWorker → workflow task worker saturated. Workflow steps are delayed.

**Important caveat (Java SDK):** Slots NOT dropping to 0 does NOT mean the worker is healthy under all circumstances. In the Temporal Java SDK, each in-progress workflow in the sticky cache holds a real JVM thread in WAITING state regardless of slot count. You must also check *JVM Thread States* and *Sticky Cache Size* — the real saturation limit is `maxWorkflowThreadCount` (default 600 threads), not the slot counter.

**See also:** Slots Used, JVM Thread States, Sticky Cache Size, Task Queue Backlog.

---

### Slots Used
**Metric:** `temporal_worker_task_slots_used` by worker_type

**What it measures:** Number of task execution slots currently occupied. `Slots Used + Slots Available = configured maximum`.

**Normal:** Fluctuates with load, well below maximum.

**Problem:**
- At maximum (= Slots Available at 0) → saturated.
- Growing steadily → tasks running slower than they arrive. Check *Activity Execution Latency*.

**See also:** Slots Available, Activity Execution Latency, Workflow Task Execution Latency.

---

## Sticky Cache

### Sticky Cache Size
**Metric:** `temporal_sticky_cache_size`

**What it measures:** Number of workflow executions held in the worker's in-memory sticky cache. In the Temporal Java SDK, each cached workflow holds at least one real JVM thread in WAITING state.

**Normal:** Grows with active workflows, bounded by `workflowCacheSize` (default 10,000).

**Problem:**
- At or near the `workflowCacheSize` limit → evictions begin. Check *Sticky Cache Forced Eviction*.
- Growing proportionally to *Workflows In Progress* → expected in the Java SDK. Each entry here = at least one JVM thread parked in WAITING state. Monitor *JVM Thread States* and *Memory Usage*.

**See also:** JVM Thread States (1 thread per cached workflow in the Java SDK), Sticky Cache Forced Eviction, Memory Usage, Workflows In Progress.

---

### Sticky Cache Forced Eviction
**Metric:** `temporal_sticky_cache_total_forced_eviction_total`

**What it measures:** Rate of workflow executions forcibly removed from the sticky cache. When evicted, the next task for that workflow must replay from full history instead of continuing in memory.

**Normal:** 0 or very low. Occasional evictions are acceptable.

**Problem:**
- Sustained eviction rate → `workflowCacheSize` is too small for your concurrent workflow count. Increase it or reduce workflow concurrency.
- High evictions AND high *Workflow Task Replay Latency* → replays triggered by evictions are causing visible latency.

**See also:** Sticky Cache Size, Sticky Cache Miss, Workflow Task Replay Latency.

---

### Sticky Cache Hit
**Metric:** `temporal_sticky_cache_hit_total`

**What it measures:** Rate of workflow tasks served from the sticky cache (no replay needed). High hit rate = efficient execution.

**Normal:** High and stable — the vast majority of tasks should hit the cache.

**Problem:**
- Dropping → cache is being evicted too often or the cache is cold (worker restarted recently). Check *Sticky Cache Forced Eviction*.
- Near 0 → `workflowCacheSize` is too small or caching is effectively disabled.

**See also:** Sticky Cache Forced Eviction, Sticky Cache Miss.

---

### Sticky Cache Miss
**Metric:** `temporal_sticky_cache_miss_total` by task_queue

**What it measures:** Rate of workflow tasks NOT found in the sticky cache, requiring full history replay. Happens when the workflow was evicted, or a different worker picks up the task.

**Normal:** Low. Some misses are expected after worker restarts.

**Problem:**
- High AND *Sticky Cache Forced Eviction* rising → cache too small. Increase `workflowCacheSize`.
- High WITHOUT evictions → workflows being routed to workers that don't have them cached (sticky queue routing issue).

> **Note:** High miss rate + high replay latency + growing schedule-to-start together indicate the worker is spending most of its time replaying rather than executing new tasks.

**See also:** Sticky Cache Forced Eviction, Workflow Task Replay Latency, RPC Requests Per Operation.

---

## Temporal Server Health

### Task Dispatch Timeouts
**Metric:** `poll_timeouts{taskqueue="payment_queue"}` — *replace `payment_queue` with your task queue name*

**What it measures:** Rate of tasks on the Temporal server that expired before any worker picked them up. Server-side metric.

**Normal:** 0.

**Problem:**
- Non-zero + *Workflow Task Empty Polls* / *Empty Activity Polls* > 0 → workers alive but cannot keep up (capacity issue).
- Non-zero + empty polls = 0 → workers stopped polling. Check for crashes or *Slots Available* at 0.

> **Note on SDK timeout metrics:** `temporal_workflow_timeout_total` and `temporal_activity_timeout_total` do not exist in Temporal Java SDK 1.32. Execution timeouts surface as failures in *Workflow Failures By Type* and *Activity Failed*.

**See also:** Slots Available, Task Queue Backlog, Workflow Task Empty Polls, Empty Activity Polls.

---

### Service Request Rate
**Metric:** `service_requests` by service_name (frontend, history, matching)

**What it measures:** Rate of internal gRPC calls handled by each Temporal server component. Frontend handles client/worker API calls. History manages workflow state transitions. Matching dispatches tasks to workers.

**Normal:** All components handling traffic proportional to workflow and activity throughput. Frontend and history dominate.

**Problem:**
- History rate very high → heavy workflow task processing or many replays. Check *Sticky Cache Miss*.
- Any component drops to 0 unexpectedly → that component has stopped.

**See also:** Service Errors by Type, Service Latency p95, Persistence Latency p95.

---

### Service Errors by Type
**Metric:** `service_error_with_type` by service_name, error_type

**What it measures:** Rate of server-side errors broken down by error type and component.

**Normal:** `serviceerror_NotFound` on `SignalWorkflowExecution` is expected (signal to already-completed workflow). Everything else should be 0.

**Problem:**
- `ResourceExhausted` → server RPS limits hit ("Per shard RPS warn limit exceeded" in server logs). Reduce request rate or scale up the Temporal server.
- `serviceerror_Unavailable` → server or database unreachable.

> **Note:** Cross-reference with *RPC Failures Per Operation* (SDK view). If the SDK sees failures but service errors are low = network problem. If both spike together = issue is inside Temporal.

**See also:** Service Request Rate, RPC Failures Per Operation, Persistence Errors.

---

### Service Latency p95 (ms)
**Metric:** `service_latency_bucket` by service_name

**What it measures:** p95 latency of internal gRPC operations within the Temporal server per component, in milliseconds.

**Normal:** Frontend: below 10–20ms. History: below 20–50ms. Matching: below 50ms.

**Problem:**
- History latency rising → state transitions are slow, most likely caused by *Persistence Latency p95* (DB slowness).
- Frontend latency rising → client-facing calls are slow → will appear as elevated *RPC Latencies*.
- Matching latency rising → task dispatch is slow → *Activity/Workflow Task Schedule To Start* will rise.

> **Note:** If *Service Latency* is elevated but *Persistence Latency* is normal, the bottleneck is Temporal's internal logic. If persistence latency is elevated, DB capacity needs attention.

**See also:** Persistence Latency p95, RPC Latencies, Activity Schedule To Start.

---

### Persistence Latency p95 (ms)
**Metric:** `persistence_latency_bucket` by operation

**What it measures:** p95 latency of database (Postgres) operations per operation type, in milliseconds. Temporal writes to the DB on every workflow state transition — it is DB-heavy by design.

**Normal:** Below 5ms for reads, below 10ms for writes in a local Postgres setup.

**Problem:**
- Any operation exceeding 10ms sustained → Temporal server slows globally. All workflow latencies increase downstream.
- `UpdateWorkflowExecution` latency high → the most frequent write (every activity completion, every signal received). DB is the primary bottleneck.

**See also:** Service Latency p95 (DB latency → server latency → SDK latency chain), Persistence Errors.

---

### Persistence Errors
**Metric:** `persistence_error_with_type` by operation, error_type

**What it measures:** Rate of database errors per operation and error category.

**Normal:** `serviceerror_NotFound` on `GetTaskQueue` at startup is expected. Everything else should be 0.

**Problem:**
- Errors on `CreateWorkflowExecution` or `UpdateWorkflowExecution` → DB write failures. Workflows may be lost or stuck silently.

**See also:** Service Errors by Type, Persistence Latency p95.

---

### Worker Saturation — Schedule-to-Start / Slots / Backlog
**Metrics:** schedule-to-start latency (activity + workflow), slots available, task queue backlog, empty polls, poller count

**What it measures:** All key worker saturation signals on one panel for fast diagnosis. Left axis: schedule-to-start p95 (ms). Right axis: slots available and backlog count.

**Normal:** Schedule-to-start low (below 100ms), slots well above 0, backlog at 0, empty polls non-zero (workers idle and ready).

**Problem — pattern 1 (worker saturated):** schedule-to-start rising + slots dropping to 0 + backlog growing → scale out workers or find what is making activities slow.

**Problem — pattern 2 (server bottleneck):** schedule-to-start rising + slots still high + empty polls still non-zero → workers are ready but the server is slow dispatching. Check *Service Latency p95*.

**Problem — pattern 3 (no pollers):** backlog growing + empty polls = 0 + poller count = 0 → workers stopped polling. Check for crashes or network issues.

> **Note:** Use this as the first panel to check when investigating worker performance problems. It consolidates signals from Slots, Workflow Task Processing, Activity Task Processing, and Task Queue Backlog panels.

**See also:** Slots Available, Task Queue Backlog, Service Latency p95, JVM Thread States.

---

## Kafka Client Metrics

### Kafka Producer: Message Rate
**Metric:** `kafka_producer_record_send_rate`, `kafka_producer_record_error_total` — standard Kafka producer metrics; filter by `client_id` label to match your producer

**What it measures:** Rate of records the Kafka producer attempts to send per second, and rate of send errors. Client-side view — reflects what the producer is doing, not what the broker confirms.

**Normal:** Send rate tracks your message production rate. Error rate is 0.

**Problem:**
- Error rate > 0 → messages failing to reach the broker. Check *Kafka Producer: Buffer Pressure* and broker health.
- Send rate drops while *Activity Throughput* stays normal → Kafka-producing activity is failing before send. Check *Activity Failed*.

**See also:** Kafka Producer: Buffer Pressure, Kafka Producer: Latency, Activity Failed.

---

### Kafka Producer: Throughput
**Metric:** `kafka_producer_outgoing_byte_rate` — standard Kafka producer metric; filter by `client_id` to match your producer

**What it measures:** Bytes per second leaving the Kafka producer.

**Normal:** Proportional to message rate × average message size.

**Problem:**
- Bytes/sec drops while record rate stays same → messages getting smaller (possible data truncation).
- Both drop together → throughput starvation. Check *Kafka Producer: Buffer Pressure* and *Slots Available (ActivityWorker)*.

**See also:** Kafka Producer: Message Rate, Kafka Producer: Buffer Pressure.

---

### Kafka Producer: Latency
**Metric:** `kafka_producer_request_latency_avg` — standard Kafka producer metric; filter by `client_id` to match your producer

**What it measures:** Average round-trip time of a produce request from the Kafka producer to the broker and back, in milliseconds. Client-measured ACK latency.

**Normal:** Below 5ms in a local or dev setup with `acks=1`.

**Problem:**
- Rising → broker is slow. Compare with Broker Produce Latency in the Kafka Cluster dashboard.
- High AND *Activity Execution Latency* elevated for Kafka-producing activities → the activity is blocking a JVM thread waiting for the ACK. Check *JVM Thread States (waiting)*.

**See also:** Kafka Producer: Waiting Threads, Activity Execution Latency, JVM Thread States, Kafka Cluster dashboard.

---

### Kafka Consumer: Message Rate
**Metric:** `kafka_consumer_fetch_manager_records_consumed_rate` — standard Kafka consumer metric; filter by `topic` label to match your consumer

**What it measures:** Rate of records consumed per second per topic.

**Normal:** Tracks the produce rate for the same topic. Consumer lag stays near 0.

**Problem:**
- Rate drops → consumer slow or not running. Check *Kafka Consumer: Lag*.
- Rate is 0 → consumer not running or lost partition assignment. Check *Kafka Consumer: Rebalance Latency*.

**See also:** Kafka Consumer: Lag, Kafka Consumer: Rebalance Latency.

---

### Kafka Consumer: Lag
**Metric:** `kafka_consumer_fetch_manager_records_lag` — standard Kafka consumer metric; filter by `topic` to match your consumer

**What it measures:** Records produced but not yet consumed, per topic. The real-time backlog of the consumer.

**Normal:** Near 0.

**Problem:**
- Growing → consumer cannot keep up with the producer.
- Lag is 0 AND message rate is 0 AND producer is producing → consumer is not running.

**See also:** Kafka Consumer: Message Rate, Kafka Consumer: Rebalance Latency.

---

### Kafka Consumer: Throughput
**Metric:** `kafka_consumer_incoming_byte_total` — standard Kafka consumer metric; filter by `client_id` to match your consumer

**What it measures:** Bytes per second incoming to the Kafka consumer.

**See also:** Kafka Consumer: Message Rate, Kafka Consumer: Lag.

---

### Kafka Consumer: Fetch Latency
**Metric:** `kafka_consumer_fetch_manager_fetch_latency_avg` — standard Kafka consumer metric; filter by `client_id` to match your consumer

**What it measures:** Average time the consumer waits for the broker to return a fetch response, in milliseconds. Includes any `fetch.max.wait.ms` wait even when records are available.

**Normal:** Below 100ms.

**Problem:**
- Very high → broker is slow, or consumer is configured with a large `fetch.max.wait.ms`.

**See also:** Kafka Consumer: Message Rate, Kafka Consumer: Lag.

---

### Kafka Consumer: Rebalance Latency
**Metric:** `kafka_consumer_coordinator_rebalance_latency_avg` — standard Kafka consumer metric; filter by `client_id` to match your consumer

**What it measures:** Average duration of consumer group rebalancing events. Rebalances temporarily pause all consumption.

**Normal:** Rare and short (below 1s). Only triggered by scaling events or consumer restarts.

**Problem:**
- Frequent or long rebalances → consumers crashing and rejoining. Check application stability.
- Rebalance spike coincides with a *Kafka Consumer: Lag* spike → consumption was paused during rebalance.

**See also:** Kafka Consumer: Lag, Kafka Consumer: Message Rate.

---

### Kafka Producer: Waiting Threads
**Metric:** `kafka_producer_waiting_threads` — standard Kafka producer metric; filter by `client_id` to match your producer

**What it measures:** Number of threads blocked waiting for buffer space in the Kafka producer's record accumulator — before the message is even sent.

**Normal:** 0.

**Problem:**
- > 0 → producer buffer is full. Broker cannot consume produce requests fast enough, or message rate exceeds buffer capacity. Check *Kafka Producer: Buffer Pressure* and *Kafka Producer: Latency*.
- Distinguish from ACK blocking: waiting threads here = blocked on buffer allocation (before send). ACK blocking shows in *JVM Thread States (waiting)* and *Activity Execution Latency*, not here.

**See also:** Kafka Producer: Buffer Pressure, Kafka Producer: Latency.

---

### Kafka Producer: Buffer Pressure
**Metric:** `kafka_producer_bufferpool_wait_ratio`, `kafka_producer_buffer_exhausted_total` — standard Kafka producer metrics; filter by `client_id` to match your producer

**What it measures:** `bufferpool_wait_ratio` is the fraction of time the producer spends waiting for buffer space (0 = no pressure, 1 = always waiting). `buffer_exhausted_total` counts complete buffer exhaustions.

**Normal:** `wait_ratio` near 0. `exhausted` counter static.

**Problem:**
- `wait_ratio` > 0.1 sustained → producer cannot send fast enough. Check *Kafka Producer: Latency*.
- `exhausted` counter incrementing → buffer was completely full. Messages at risk of being dropped.

**See also:** Kafka Producer: Waiting Threads, Kafka Producer: Latency.

---

## Business Metrics

### Rejected Payments — Total by Reason **[Custom]**
**Metric:** `payment_rejected_total` by reason (cumulative) — *replace with your application rejection counter*

**What it measures:** Cumulative total rejected payments grouped by reason since startup. Unlike the rate panel, shows the running total for post-incident review.

**Normal:** Grows at a rate consistent with the *Payment Rejections/sec* panel.

**Problem:**
- One reason category disproportionately large → systematic failure on that path.

**See also:** Payment Rejections/sec by Reason.

---

## JVM Thread States

### JVM Thread States (stacked)
**Metric:** `jvm_threads_states_threads` by state

**What it measures:** JVM thread count broken down by lifecycle state: `waiting`, `timed-waiting`, `blocked`, `runnable`. Counts all threads in the JVM process — Temporal SDK threads, activity threads, Vert.x event loop threads, thread pool workers, internal JVM threads.

**Normal:**
- `runnable` tracks actual CPU work, proportional to load.
- `waiting` has a stable baseline (~20–30 threads in this setup) from idle thread pool workers. Grows proportionally with *Workflows In Progress* in the Temporal Java SDK — each workflow in the sticky cache holds at least one real JVM thread in WAITING state.
- `timed-waiting` is low.
- `blocked` near 0 (contention on a Java monitor lock).

**Problem:**
- `waiting` grows proportionally to *Workflows In Progress* → expected in the Temporal Java SDK. Each workflow in the sticky cache holds a thread. This is by design. The limit is `maxWorkflowThreadCount` (default 600).
- `waiting` grows faster than *Workflows In Progress* → activities are holding extra threads while waiting for I/O (blocking Kafka send, slow REST call). Identify the slow activity in *Activity Execution Latency*.
- `blocked` > 0 sustained → thread contention on a monitor. Rare; usually a concurrency bug.
- `waiting` approaches ~600 → worker is near the Java SDK thread limit. New workflow tasks will be rejected.

**See also:** Workflows In Progress, Sticky Cache Size, Activity Execution Latency, JVM Parked Threads.

---

### JVM Parked Threads (waiting + timed-waiting)
**Metrics:** `jvm_threads_states_threads{state="waiting|timed-waiting"}`, `fraud_smallrye_send_duration_seconds_*` — *right axis is standard JVM; left axis timer is [Custom] — replace with your own blocking operation timer*

**What it measures:** Right axis: standard JVM metric — total threads in `waiting` + `timed-waiting` states, all parked threads regardless of reason. Left axis: **[Custom]** average duration of a specific application-level blocking call (ms). In this setup it is the fraud Kafka send — replace `fraud_smallrye_send_duration_seconds` with whatever operation you want to correlate against thread pressure.

**Normal:** Right axis grows roughly in proportion to *Workflows In Progress* — that is the Temporal sticky cache thread baseline. Left axis stays low when the dependency is fast.

**Problem:**
- Left axis rising AND right axis rising faster than *Workflows In Progress* → the blocking call is contributing extra thread accumulation on top of the Temporal baseline. The dependency (Kafka, REST, DB) is slow and activities are piling up.
- Right axis rising with left axis flat → thread growth is from workflow concurrency (expected Temporal Java SDK behavior), not from this specific blocking call.
- Right axis approaching 600 → worker near `maxWorkflowThreadCount`. New workflow tasks will be rejected.

**See also:** JVM Thread States (stacked), Workflows In Progress, Sticky Cache Size, Activity Execution Latency.
