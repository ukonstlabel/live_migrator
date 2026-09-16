#!/usr/bin/env python3
"""Align a service JVM's compilation/deoptimization unified logs with the client timeline.

Both are anchored to the same absolute t0 (the attach moment), so every JIT event can be placed
in the same t_rel_s axis the latency timeline uses. Answers: is there a deoptimisation burst right
after the migration, and does the recompilation window coincide with the return-to-SLO window?

Usage: jit_correlate.py RUN_DIR [--hot SUBSTR ...]
"""
import csv, re, sys, datetime, collections
from pathlib import Path

run = Path(sys.argv[1])
hot_pat = [a for a in sys.argv[2:] if not a.startswith("--")] or ["service.", "ServiceMain", "OldUser", "NewUser", "User::"]

t0 = int((run / "t0.txt").read_text().strip())

TS = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+[+\-]\d{4})\]\[\s*([\d,.]+)s\]\s*(.*)$")

def parse(path):
    out = []
    if not path.exists():
        return out
    for line in path.read_text(errors="replace").splitlines():
        m = TS.match(line)
        if not m:
            continue
        dt = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z")
        out.append((dt.timestamp() * 1000.0 - t0) / 1000.0, ) + (m.group(3),) if False else out.append(
            ((dt.timestamp() * 1000.0 - t0) / 1000.0, m.group(3)))
    return out

deopts = parse(run / "deoptimization.log")
comps  = parse(run / "compilation.log")

# ─── timeline ───
rows = []
tl = run / "timeline.csv"
if tl.exists():
    with open(tl) as f:
        rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(f)]
pre = [r for r in rows if r["t_rel_s"] < 0]
import statistics
base_p50 = statistics.median([r["p50_ms"] for r in pre]) if pre else float("nan")
base_p99 = statistics.median([r["p99_ms"] for r in pre]) if pre else float("nan")

print(f"\n=== {run.name} ===  baseline p50={base_p50:.3f}ms  p99={base_p99:.3f}ms")

# ─── per-second histogram: deopts vs compiles vs latency ───
def hist(events, lo, hi):
    h = collections.Counter()
    for t, _ in events:
        if lo <= t < hi:
            h[int(t // 1)] += 1
    return h

LO, HI = -10, 25
hd, hc = hist(deopts, LO, HI), hist(comps, LO, HI)
print(f"\n t_rel  deopts  compiles |  p50_ms   p99_ms   avail   5xx")
print(f"------  ------  -------- | -------  -------  ------  ----")
by_t = {int(r["t_rel_s"]): r for r in rows}
for s in range(LO, HI):
    r = by_t.get(s)
    lat = (f"{r['p50_ms']:7.2f}  {r['p99_ms']:7.2f}  {r['availability']:6.4f}  {int(r['status5xx']):4d}"
           if r else " " * 30)
    bar = "#" * min(40, hd[s])
    print(f"{s:6d}  {hd[s]:6d}  {hc[s]:8d} | {lat}  {bar}")

# ─── totals split at t0 ───
def split(ev):
    return sum(1 for t, _ in ev if t < 0), sum(1 for t, _ in ev if t >= 0)
print(f"\ntotals: deopts pre-t0={split(deopts)[0]}  post-t0={split(deopts)[1]}   "
      f"compiles pre-t0={split(comps)[0]}  post-t0={split(comps)[1]}")

# ─── deopt reasons after t0 ───
REASON = re.compile(r"trap_bci=\d+\s+(\S+)\s+(\S+)")
METH = re.compile(r"level=\d+\s+(\S+)")
def summarize(ev, lo, hi, label, n=12):
    sel = [m for t, m in ev if lo <= t < hi]
    print(f"\n{label}  ({len(sel)} events)")
    reasons = collections.Counter()
    meths = collections.Counter()
    for m in sel:
        r = REASON.search(m)
        reasons[r.group(1) if r else "?"] += 1
        mm = METH.search(m)
        meths[mm.group(1).split("(")[0] if mm else "?"] += 1
    for k, v in reasons.most_common(n):
        print(f"    reason {k:<24} {v}")
    print("   top methods:")
    for k, v in meths.most_common(n):
        print(f"    {v:5d}  {k}")

summarize(deopts, -1e9, 0, "deopt reasons BEFORE t0")
summarize(deopts, 0, 1e9, "deopt reasons AFTER t0")

# ─── app-relevant compile/deopt events after t0 ───
print("\napp-relevant JIT events after t0 (first 60):")
shown = 0
for t, m in sorted([e for e in deopts if e[0] >= 0] + [(t, "COMPILE " + m) for t, m in comps if t >= 0]):
    if any(p in m for p in hot_pat):
        kind = "COMPILE" if m.startswith("COMPILE ") else "DEOPT  "
        print(f"  {t:+8.3f}s  {kind}  {m.replace('COMPILE ', '')[:150]}")
        shown += 1
        if shown >= 60:
            break
if shown == 0:
    print("  (none matched)", hot_pat)
