#!/usr/bin/env python3
"""Recompute robust recovery/downtime metrics from per-run timeline.csv files.

Why not use summary.json's recovery_sec: it requires a return to baseline_p99 x factor.
With a ~3ms baseline and a post-update steady state that is legitimately higher (the heap
grew, so more GC pressure), that threshold is never met and the metric saturates at the
window end. Here we instead measure, from the timeline:

  hard_downtime_s   - post-t0 buckets where the process was unreachable (conn_refused or
                      timeout > 0). This is the real "downtime".
  intake_pause_s    - post-t0 buckets with 5xx but no conn_refused (S0's paused POST intake).
  avail_recovery_s  - time from t0 until availability stays >= 99.9% to the end of the window.
  slo_recovery_s    - time from t0 until availability >= 99.9% AND p99 <= SLO_MS, held to end
                      (absolute SLO, comparable across strategies; the JIT-warmup tail of a
                      cold JVM shows up here).
  max_p99_ms        - worst p99 spike after t0.
  post_p99_ms       - settled p99 (median over the last SETTLE_S post buckets).

Usage: recompute.py BASE_DIR [SLO_MS]   (default SLO_MS=50)
"""
import csv
import statistics
import sys
from pathlib import Path
from collections import defaultdict

base = Path(sys.argv[1])
SLO_MS = float(sys.argv[2]) if len(sys.argv) > 2 else 50.0
SETTLE_S = 10            # window (s) at the tail used to estimate the post-update steady state
AVAIL_OK = 0.999

def load(tl):
    rows = []
    with open(tl) as f:
        for r in csv.DictReader(f):
            rows.append({k: float(v) for k, v in r.items()})
    return rows

def analyze(rows):
    pre = [r for r in rows if r["t_rel_s"] < 0]
    post = [r for r in rows if r["t_rel_s"] >= 0]
    bsec = 1.0  # buckets are 1s
    baseline = statistics.median([r["p99_success_ms"] for r in pre]) if pre else float("nan")
    tail = [r for r in post if r["t_rel_s"] >= (post[-1]["t_rel_s"] - SETTLE_S)] if post else []
    post_p99 = statistics.median([r["p99_ms"] for r in tail]) if tail else float("nan")

    hard = sum(bsec for r in post if r["conn_refused"] > 0 or r["timeout"] > 0)
    intake = sum(bsec for r in post if r["status5xx"] > 0 and r["conn_refused"] == 0)
    max_p99 = max((r["p99_ms"] for r in post), default=float("nan"))

    def first_stable(pred):
        # earliest post bucket s.t. it and all later buckets satisfy pred
        for i, r in enumerate(post):
            if all(pred(post[j]) for j in range(i, len(post))):
                return post[i]["t_rel_s"]
        return float("nan")

    avail_rec = first_stable(lambda r: r["availability"] >= AVAIL_OK)
    slo_rec = first_stable(lambda r: r["availability"] >= AVAIL_OK and r["p99_ms"] <= SLO_MS)
    errs = {k: sum(r[k] for r in rows) for k in
            ("status5xx", "conn_refused", "timeout", "saturated", "other_error")}
    return dict(baseline=baseline, post_p99=post_p99, hard=hard, intake=intake,
                max_p99=max_p99, avail_rec=avail_rec, slo_rec=slo_rec, errs=errs)

runs = defaultdict(list)
for tl in sorted(base.glob("*/timeline.csv")):
    strat = tl.parent.name.split("-rep")[0]
    runs[strat].append(analyze(load(tl)))

def med(xs):
    xs = [x for x in xs if x == x]  # drop NaN
    return statistics.median(xs) if xs else float("nan")

def f(x, u=""):
    return "n/a" if x != x else f"{x:.1f}{u}"

print(f"\nSLO = {SLO_MS:.0f}ms p99 ; availability OK = {AVAIL_OK*100:.1f}% ; "
      f"settle window = {SETTLE_S}s ; medians over replicas\n")
hdr = (f"{'strategy':<10}{'reps':>5}{'baseline':>10}{'post_p99':>10}"
       f"{'hard_down':>11}{'intake':>9}{'avail_rec':>11}{'slo_rec':>9}{'max_p99':>10}")
print(hdr); print("-" * len(hdr))
for s in sorted(runs):
    ds = runs[s]
    print(f"{s:<10}{len(ds):>5}"
          f"{f(med([d['baseline'] for d in ds]),'ms'):>10}"
          f"{f(med([d['post_p99'] for d in ds]),'ms'):>10}"
          f"{f(med([d['hard'] for d in ds]),'s'):>11}"
          f"{f(med([d['intake'] for d in ds]),'s'):>9}"
          f"{f(med([d['avail_rec'] for d in ds]),'s'):>11}"
          f"{f(med([d['slo_rec'] for d in ds]),'s'):>9}"
          f"{f(med([d['max_p99'] for d in ds]),'ms'):>10}")

print("\nerror totals (summed across reps), by kind:")
for s in sorted(runs):
    agg = defaultdict(float)
    for d in runs[s]:
        for k, v in d["errs"].items():
            agg[k] += v
    parts = ", ".join(f"{k}={int(v)}" for k, v in agg.items() if v)
    print(f"  {s}: {parts or 'none'}")

print("\nhard_down = process unreachable (conn_refused/timeout); intake = paused POST (503, S0)")
print("avail_rec = t0->availability stably >=99.9%; slo_rec = t0->(avail OK & p99<=SLO) held to end")
