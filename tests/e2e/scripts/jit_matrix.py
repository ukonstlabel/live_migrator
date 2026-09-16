#!/usr/bin/env python3
"""Aggregate the JIT-attribution matrix: latency shape around t0 vs JIT activity around t0.

For each arm it reports, as medians over replicas:
  base_p50/p99  pre-t0 baseline
  spike_p99     worst post-t0 p99 bucket (the visible dip)
  dip_s         post-t0 seconds with p99 > 5x baseline p99 (contiguous from t0)
  slo_rec_s     t0 -> (availability>=99.9% and p99<=50ms) held to end of window
  p50_tail_s    t0 -> p50 back within 10% of baseline p50, held for 3 consecutive buckets
  tail_excess   mean p50 excess over baseline across the +2..+15s window (the "warm-up tail")
  dopt0_2       deoptimisations in [t0, t0+2s)     hot_dopt: those in application classes
  comp0_2       compilations in [t0, t0+2s)
  gc0_5         GC pauses in [t0, t0+5s)  / total GC ms in that window

Usage: jit_matrix.py BASE_DIR
"""
import csv, re, sys, statistics, datetime, collections
from pathlib import Path

base = Path(sys.argv[1])
TS = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+[+\-]\d{4})\]\[\s*[\d,.]+s\]\s*(.*)$")
GCPAUSE = re.compile(r"Pause .*?([\d,.]+)ms\s*$")
APP = ("service.", "migration.", "probe.", "migrator.")


def events(path, t0):
    out = []
    if not path.exists():
        return out
    for line in path.read_text(errors="replace").splitlines():
        m = TS.match(line)
        if m:
            dt = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z")
            out.append(((dt.timestamp() * 1000.0 - t0) / 1000.0, m.group(2)))
    return out


def analyze(run):
    t0 = int((run / "t0.txt").read_text().strip())
    with open(run / "timeline.csv") as f:
        rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(f)]
    rows = [r for r in rows if r["total"] > 50]          # drop the ragged final bucket
    pre = [r for r in rows if r["t_rel_s"] < 0]
    post = [r for r in rows if r["t_rel_s"] >= 0]
    b50 = statistics.median(r["p50_ms"] for r in pre)
    b99 = statistics.median(r["p99_ms"] for r in pre)

    spike = max((r["p99_ms"] for r in post), default=float("nan"))
    dip = 0.0
    for r in post:                                        # contiguous from t0
        if r["p99_ms"] > 5 * b99:
            dip += 1.0
        else:
            break

    def first_stable(pred, hold=None):
        for i, r in enumerate(post):
            rng = range(i, len(post)) if hold is None else range(i, min(i + hold, len(post)))
            if all(pred(post[j]) for j in rng):
                return r["t_rel_s"]
        return float("nan")

    slo = first_stable(lambda r: r["availability"] >= 0.999 and r["p99_ms"] <= 50.0)
    p50tail = first_stable(lambda r: r["p50_ms"] <= 1.10 * b50, hold=3)
    # measure the settled tail AFTER the dip, so a long dip does not leak into it
    win = [r for r in post if dip + 1 <= r["t_rel_s"] <= dip + 15]
    tail_excess = (statistics.mean(r["p50_ms"] for r in win) / b50 - 1.0) * 100 if win else float("nan")

    d = events(run / "deoptimization.log", t0)
    c = events(run / "compilation.log", t0)
    g = events(run / "gc.log", t0)
    dopt = sum(1 for t, _ in d if 0 <= t < 2)
    hot = sum(1 for t, m in d if 0 <= t < 2 and any(p in m for p in APP))
    comp = sum(1 for t, _ in c if 0 <= t < 2)
    gcn, gcms = 0, 0.0
    for t, m in g:
        if 0 <= t < 5:
            mm = GCPAUSE.search(m)
            if mm:
                gcn += 1
                gcms += float(mm.group(1).replace(",", "."))
    return dict(b50=b50, b99=b99, spike=spike, dip=dip, slo=slo, p50tail=p50tail,
                tail_excess=tail_excess, dopt=dopt, hot=hot, comp=comp, gcn=gcn, gcms=gcms,
                logged=(run / "deoptimization.log").exists())


arms = collections.defaultdict(list)
for run in sorted(base.glob("*/timeline.csv")):
    run = run.parent
    arm = run.name.split("-rep")[0]
    try:
        arms[arm].append(analyze(run))
    except Exception as e:                                 # noqa: BLE001
        print(f"  ! {run.name}: {e}", file=sys.stderr)


def med(ds, k):
    xs = [d[k] for d in ds if d[k] == d[k]]
    return statistics.median(xs) if xs else float("nan")


def f(x, w=8, p=1):
    return "n/a".rjust(w) if x != x else f"{x:.{p}f}".rjust(w)


DESC = {
    "A":   "migration, instrumented",
    "A0":  "migration, no JIT logging",
    "B":   "migration, -XX:-Inline",
    "Cbi": "migration, call site pre-warmed bimorphic",
    "Cmg": "migration, call site pre-warmed megamorphic",
    "D":   "class-load only (no patching)",
    "Di":  "class-load + M/5 instances (no patching)",
    "E":   "no-op attach",
    "F":   "migration, -XX:TypeProfileWidth=0 (deopt suppressed)",
    "G":   "class-load + M/5 inst, -XX:TypeProfileWidth=0",
    "Di1": "class-load + ONE instance (no patching)",
}
ORDER = ["A", "A0", "B", "Cbi", "Cmg", "F", "D", "Di", "Di1", "G", "E"]

print(f"\nJIT-attribution matrix — {base}   (medians over replicas)\n")
hdr = (f"{'arm':<5}{'n':>2} {'base_p50':>9}{'base_p99':>9}{'spike_p99':>10}{'dip_s':>7}"
       f"{'slo_rec':>9}{'p50tail':>9}{'tail_+%':>9}{'deopt0-2':>10}{'hot':>5}{'comp0-2':>9}"
       f"{'gc0-5':>7}{'gc_ms':>8}   description")
print(hdr); print("-" * len(hdr))
for a in ORDER + [x for x in sorted(arms) if x not in ORDER]:
    if a not in arms:
        continue
    ds = arms[a]
    logged = ds[0]["logged"]
    jit = (f"{f(med(ds,'dopt'),10,0)}{f(med(ds,'hot'),5,0)}{f(med(ds,'comp'),9,0)}"
           f"{f(med(ds,'gcn'),7,0)}{f(med(ds,'gcms'),8,1)}") if logged else " " * 39
    print(f"{a:<5}{len(ds):>2} {f(med(ds,'b50'),9,3)}{f(med(ds,'b99'),9,3)}{f(med(ds,'spike'),10,1)}"
          f"{f(med(ds,'dip'),7,1)}{f(med(ds,'slo'),9,1)}{f(med(ds,'p50tail'),9,1)}"
          f"{f(med(ds,'tail_excess'),9,1)}{jit}   {DESC.get(a,'')}")
print("\ndip_s = contiguous post-t0 seconds with p99 > 5x baseline; p50tail = t0 -> p50 back within")
print("10% of baseline (held 3s); tail_+% = mean p50 excess over baseline across the 15s AFTER the dip ends;")
print("hot = deopts in service./migration./probe./migrator. classes.")
