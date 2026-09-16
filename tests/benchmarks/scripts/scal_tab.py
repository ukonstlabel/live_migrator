#!/usr/bin/env python3
"""Tabulate the scalability JMH JSON files into per-axis tables with growth ratios."""
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])


def load(name):
    f = out / name
    if not f.exists():
        return []
    rows = []
    for b in json.loads(f.read_text()):
        p = b["params"]
        rows.append({
            "m": int(p.get("m", 0)),
            "fanout": int(p.get("fanout", 0)),
            "payloadSize": int(p.get("payloadSize", 0)),
            "score": b["primaryMetric"]["score"],
            "err": float(b["primaryMetric"]["scoreError"]),  # JMH writes "NaN" when there are too few iterations
        })
    return rows


def table(title, rows, axis, fixed_note):
    if not rows:
        print(f"\n## {title}\n  (no data)")
        return
    rows = sorted(rows, key=lambda r: r[axis])
    print(f"\n## {title}   [{fixed_note}]")
    print(f"  {axis:>12} {'ms/op':>12} {'±99.9%':>10} {'x prev':>8} {'x axis':>8}")
    prev = None
    for r in rows:
        ratio_t = "" if prev is None else f"{r['score']/prev[0]:.2f}"
        ratio_a = "" if prev is None or prev[1] == 0 else f"{r[axis]/prev[1]:.2f}"
        print(f"  {r[axis]:>12} {r['score']:>12.2f} {r['err']:>10.2f} {ratio_t:>8} {ratio_a:>8}")
        prev = (r["score"], r[axis])


table("m axis — object count V (fanout=0)", load("axis-m.json"), "m", "fanout=0, payload=64")
table("fanout axis — refs/node, connectivity (m=3000)", load("axis-fanout.json"), "fanout", "m=3000, payload=64")
table("payloadSize axis — bytes/node, heap (m=20000)", load("axis-payload.json"), "payloadSize", "m=20000, fanout=0")

print("\n## GC axis (m=50000, fanout=0, payload=64)")
print(f"  {'GC':>14} {'ms/op':>12} {'±99.9%':>10}")
for gc in ("G1GC", "ZGC", "ShenandoahGC", "ParallelGC"):
    rows = load(f"axis-gc-{gc}.json")
    if rows:
        r = rows[0]
        print(f"  {gc:>14} {r['score']:>12.2f} {r['err']:>10.2f}")
    else:
        print(f"  {gc:>14} {'n/a':>12}")

print("\n'x prev' = ms ratio vs previous row; 'x axis' = axis-value ratio. "
      "Linear ⇒ x prev ≈ x axis.")
