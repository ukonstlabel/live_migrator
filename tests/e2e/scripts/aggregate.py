#!/usr/bin/env python3
"""Aggregate per-replica summary.json files of each strategy into a comparison table (medians)."""
import json
import statistics
import sys
from pathlib import Path
from collections import defaultdict

base = Path(sys.argv[1])
runs = defaultdict(list)
for sf in sorted(base.glob("*/summary.json")):
    try:
        d = json.loads(sf.read_text())
    except Exception as e:  # noqa: BLE001
        print(f"  skip {sf}: {e}")
        continue
    # strategy = prefix before '-rep'
    label = d.get("label", sf.parent.name)
    strat = label.split("-rep")[0]
    runs[strat].append(d)


def med(values):
    vals = [v for v in values if v is not None]
    return statistics.median(vals) if vals else None


def fmt(v, unit=""):
    return "n/a" if v is None else f"{v:.1f}{unit}"


print()
hdr = f"{'strategy':<10} {'reps':>4} {'baseline_p99':>13} {'downtime':>9} "\
      f"{'recovery':>9} {'max_p99':>9} {'errors':>10}"
print(hdr)
print("-" * len(hdr))
for strat in sorted(runs):
    ds = runs[strat]
    print(f"{strat:<10} {len(ds):>4} "
          f"{fmt(med([d['baseline_p99_ms'] for d in ds]), 'ms'):>13} "
          f"{fmt(med([d['downtime_sec'] for d in ds]), 's'):>9} "
          f"{fmt(med([d['recovery_sec'] for d in ds]), 's'):>9} "
          f"{fmt(med([d['max_p99_ms'] for d in ds]), 'ms'):>9} "
          f"{int(med([d['total_errors'] for d in ds]) or 0):>10}")
print()
print("downtime = total duration of buckets with availability <50% after t0")
print("recovery = time from t0 until the p99 of successful requests stably returns below baseline×factor")
print("           (cold-JVM JIT-warmup tail; ≈ 0 for S0)")
