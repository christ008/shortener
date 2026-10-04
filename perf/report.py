#!/usr/bin/env python3
"""Turns the output of perf/bench.sh for several variants into a Markdown table.
   perf/report.py RESULTS_DIR   (every subdirectory is one variant)"""
import json, pathlib, re, sys

root = pathlib.Path(sys.argv[1])
variants = sorted(p for p in root.iterdir() if (p / "summary.json").exists())


def ms(seconds):
    return "n/a" if seconds in (None, "null") else f"{float(seconds) * 1000:.1f}"


def k6_metric(path, name, field):
    data = json.loads(path.read_text())["metrics"]
    return data[name][field]


def peak_memory(path):
    peak = 0.0
    for line in path.read_text().splitlines():
        match = re.search(r"([\d.]+)(MiB|GiB)\s*/", line)
        if match:
            peak = max(peak, float(match.group(1)) * (1024 if match.group(2) == "GiB" else 1))
    return peak


def avg_cpu(path):
    values = [float(m.group(1)) for m in (re.match(r"\d+ ([\d.]+)%", l) for l in path.read_text().splitlines()) if m]
    return sum(values) / len(values) if values else 0


print("| | " + " | ".join(v.name for v in variants) + " |")
print("|---|" + "---|" * len(variants))
rows = []
summaries = {v.name: json.loads((v / "summary.json").read_text()) for v in variants}
rows.append(("Time until ready (ms, from `docker run`)", [str(s["ready_ms"]) for s in summaries.values()]))
rows.append(("Spring startup", [re.sub(r"Started \S+ in ", "", s["started"]) for s in summaries.values()]))
rows.append(("Memory when idle", [s["idle_mem"] for s in summaries.values()]))
rows.append(("Peak memory under load (MiB)", [f"{peak_memory(v / 'docker-stats.txt'):.0f}" for v in variants]))
for run in summaries[variants[0].name]["runs"]:
    name = run["name"]
    label = f"{run['rate']} req/s for {run['duration']}"
    cells = {}
    for v in variants:
        r = next(x for x in summaries[v.name]["runs"] if x["name"] == name)
        k6 = v / f"{name}.k6.json"
        achieved = k6_metric(k6, "http_reqs", "rate")
        failed = k6_metric(k6, "http_req_failed", "value") * 100
        cells[v.name] = r, achieved, failed, k6
    rows.append((f"**{label}**", [""] * len(variants)))
    rows.append(("achieved req/s", [f"{c[1]:.0f}" for c in cells.values()]))
    rows.append(("failed requests", [f"{c[2]:.2f}%" for c in cells.values()]))
    rows.append(("redirect p50 / p95 / p99 (ms, server)", [f"{ms(c[0]['server']['redirect_p50'])} / {ms(c[0]['server']['redirect_p95'])} / {ms(c[0]['server']['redirect_p99'])}" for c in cells.values()]))
    rows.append(("create p50 / p99 (ms, server)", [f"{ms(c[0]['server']['create_p50'])} / {ms(c[0]['server']['create_p99'])}" for c in cells.values()]))
    rows.append(("redirect p95 (ms, as k6 saw it)", [f"{k6_metric(c[3], 'redirect_latency', 'p(95)'):.1f}" for c in cells.values()]))
    rows.append(("pool acquire max (ms) / timeouts", [f"{ms(c[0]['server']['hikari_acquire_max'])} / {float(c[0]['server']['hikari_timeouts'] or 0):.0f}" for c in cells.values()]))
for label, cells in rows:
    print(f"| {label} | " + " | ".join(cells) + " |")
