#!/usr/bin/env python3
"""
Export Temporal workflow activity durations from Jaeger to CSV.

Each row represents one workflow execution:
  workflowId, runId, start_timestamp, end_timestamp, total_ms,
  start_api_ms, signal_api_ms, <activity>_ms ...

  total_ms = end_timestamp - start_timestamp  (includes signal wait time)

Usage:
    python export_traces.py [--jaeger http://localhost:16686] [--limit 200] [--out report.csv]
"""

import argparse
import csv
import sys
from datetime import datetime, timezone

import requests

ACTIVITY_PREFIX = "RunActivity:"
WORKFLOW_TRACE_MIN_SPANS = 8   # workflow traces have many spans
SIGNAL_TRACE_MAX_SPANS  = 5    # signal traces are short (3 spans)


def fetch_traces(jaeger_url: str, service: str, limit: int) -> list[dict]:
    url = f"{jaeger_url}/api/traces"
    resp = requests.get(url, params={"service": service, "limit": limit}, timeout=10)
    resp.raise_for_status()
    return resp.json().get("data", [])


def get_tag(span: dict, key: str) -> str | None:
    for tag in span.get("tags", []):
        if tag["key"] == key:
            return str(tag["value"])
    return None


def us_to_iso(microseconds: int) -> str:
    dt = datetime.fromtimestamp(microseconds / 1_000_000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"  # trim to ms


def extract_workflow_row(trace: dict) -> dict | None:
    spans = trace["spans"]
    if len(spans) < WORKFLOW_TRACE_MIN_SPANS:
        return None

    workflow_id = None
    run_id = None
    activity_durations: dict[str, int] = {}
    root_span = None
    max_end_us = 0

    for span in spans:
        op = span["operationName"]
        start_us = span["startTime"]   # microseconds since epoch
        dur_us = span["duration"]      # microseconds
        end_us = start_us + dur_us

        # Track overall end time across all spans
        if end_us > max_end_us:
            max_end_us = end_us

        # Root span = no references (or the POST /payments span)
        if not span.get("references"):
            root_span = span

        if workflow_id is None:
            workflow_id = get_tag(span, "workflowId")
        if run_id is None:
            run_id = get_tag(span, "runId")

        if op.startswith(ACTIVITY_PREFIX):
            name = op[len(ACTIVITY_PREFIX):]
            activity_durations[name] = round(dur_us / 1000)

    if "PublishCompleted" in activity_durations:
        status = "COMPLETED"
    elif "PublishRejected" in activity_durations:
        status = "REJECTED"
    else:
        status = "IN_FLIGHT"

    # Merge the two mutually-exclusive terminal activities into one column (after status detection)
    for original in ("PublishCompleted", "PublishRejected"):
        if original in activity_durations:
            activity_durations["Publish"] = activity_durations.pop(original)

    if not workflow_id or not root_span or not activity_durations:
        return None

    start_us = root_span["startTime"]
    total_ms = round((max_end_us - start_us) / 1000)
    start_api_ms = round(root_span["duration"] / 1000)

    return {
        "workflowId":      workflow_id,
        "runId":           run_id or "",
        "status":          status,
        "start_timestamp": us_to_iso(start_us),
        "end_timestamp":   us_to_iso(max_end_us),
        "total_ms":        total_ms,
        "start_api_ms":    start_api_ms,
        "signal_api_ms":   None,           # filled in later by joining signal traces
        "activities":      activity_durations,
    }


def extract_signal_row(trace: dict) -> dict | None:
    spans = trace["spans"]
    if len(spans) > SIGNAL_TRACE_MAX_SPANS:
        return None

    root_span = next((s for s in spans if not s.get("references")), None)
    if root_span is None:
        return None

    # workflowId lives on the Temporal child spans, not the HTTP root
    workflow_id = None
    for span in spans:
        workflow_id = get_tag(span, "workflowId")
        if workflow_id:
            break

    if not workflow_id:
        return None

    return {
        "workflowId":    workflow_id,
        "signal_api_ms": round(root_span["duration"] / 1000),
    }


def main():
    parser = argparse.ArgumentParser(description="Export Temporal traces to CSV")
    parser.add_argument("--jaeger",   default="http://localhost:16686")
    parser.add_argument("--service",  default="payment-workflow")
    parser.add_argument("--limit",    type=int, default=200)
    parser.add_argument("--out",      default="report.csv")
    args = parser.parse_args()

    print(f"Fetching traces from {args.jaeger} (service={args.service}, limit={args.limit})...")
    traces = fetch_traces(args.jaeger, args.service, args.limit)
    print(f"  Got {len(traces)} trace(s)")

    workflow_rows: dict[str, dict] = {}   # runId → row
    signal_index:  dict[str, int]  = {}   # workflowId → signal_api_ms

    for trace in traces:
        row = extract_workflow_row(trace)
        if row:
            workflow_rows[row["runId"]] = row
            continue

        sig = extract_signal_row(trace)
        if sig:
            signal_index[sig["workflowId"]] = sig["signal_api_ms"]

    # Join signal timing into workflow rows
    for row in workflow_rows.values():
        row["signal_api_ms"] = signal_index.get(row["workflowId"], "")

    if not workflow_rows:
        print("No workflow traces found.")
        sys.exit(1)

    rows = sorted(workflow_rows.values(), key=lambda r: r["start_timestamp"])

    all_activity_names = {a for r in rows for a in r["activities"]}

    # Columns in natural execution order, with signal_api_ms interleaved after ReserveFunds
    # start_api → ReserveFunds → signal_api → Transfer → Publish (or straight to Publish if rejected)
    KNOWN_ORDER = ["start_api_ms", "ReserveFunds_ms", "signal_api_ms", "Transfer_ms", "Publish_ms"]
    known_set = {"start_api_ms", "signal_api_ms", "ReserveFunds_ms", "Transfer_ms", "Publish_ms"}
    extra_cols = sorted(f"{a}_ms" for a in all_activity_names if f"{a}_ms" not in known_set)

    fixed_meta = ["workflowId", "runId", "status", "start_timestamp", "end_timestamp", "total_ms"]
    fieldnames = fixed_meta + KNOWN_ORDER + extra_cols

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            csv_row = {k: row[k] for k in fixed_meta}
            csv_row["start_api_ms"]  = row["start_api_ms"]
            csv_row["signal_api_ms"] = row["signal_api_ms"]
            for col in [f"{a}_ms" for a in all_activity_names] + extra_cols:
                a = col[:-3]  # strip _ms
                csv_row[col] = row["activities"].get(a, "")
            writer.writerow(csv_row)

    print(f"  Exported {len(rows)} workflow(s) → {args.out}")
    print()

    # Terminal preview
    cols_preview = ["workflowId", "status", "total_ms"] + KNOWN_ORDER + extra_cols
    header = "  ".join(f"{c:<22}" for c in cols_preview)
    print(header)
    print("-" * len(header))
    for row in rows:
        vals = [row.get("workflowId",""), row.get("status",""), str(row.get("total_ms",""))]
        for col in KNOWN_ORDER:
            if col.endswith("_ms") and col not in ("start_api_ms", "signal_api_ms"):
                vals.append(str(row["activities"].get(col[:-3], "")))
            else:
                vals.append(str(row.get(col, "")))
        for col in extra_cols:
            vals.append(str(row["activities"].get(col[:-3], "")))
        print("  ".join(f"{v:<22}" for v in vals))


if __name__ == "__main__":
    main()
