#!/usr/bin/env python3
"""Summarises the GC lines (-XX:+PrintGC) in an application log of a GraalVM native image: how often it collects,
how long it pauses and whether the live heap after collections grows.   perf/gc-summary.py APP_LOG"""
import re,sys
def parse(path):
    rows=[]
    for l in open(path):
        m=re.search(r'\[([\d.]+)s\] GC\(\d+\) Pause (Incremental GC|Full GC) \(([^)]*)\) ([\d.]+)M->([\d.]+)M ([\d.]+)ms',l)
        if m: rows.append((float(m.group(1)),m.group(2),m.group(3),float(m.group(4)),float(m.group(5)),float(m.group(6))))
    return rows
if __name__=="__main__":
    rows=parse(sys.argv[1]); n=len(rows)
    inc=[r for r in rows if r[1]=="Incremental GC"]; full=[r for r in rows if r[1]=="Full GC"]
    dur=rows[-1][0]-rows[0][0]
    print(f"{n} collections in {dur:.0f}s: {len(inc)} incremental ({len(inc)/dur:.1f}/s), {len(full)} full ({len(full)/dur:.2f}/s)")
    print(f"total pause {sum(r[5] for r in rows)/1000:.1f}s = {sum(r[5] for r in rows)/1000/dur*100:.1f}% of time; max pause {max(r[5] for r in rows):.0f}ms")
    for name,sel in (("incremental",inc),("full",full)):
        if sel:
            q=len(sel)//4 or 1
            print(f"{name}: live after GC first quarter avg {sum(r[4] for r in sel[:q])/q:.1f}MB, last quarter avg {sum(r[4] for r in sel[-q:])/q:.1f}MB, max {max(r[4] for r in sel):.1f}MB; avg pause {sum(r[5] for r in sel)/len(sel):.1f}ms")
    causes={}
    for r in rows: causes[(r[1],r[2])]=causes.get((r[1],r[2]),0)+1
    print("causes:",causes)
