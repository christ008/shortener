#!/usr/bin/env python3
"""Class histogram of an HPROF heap dump, such as the one a native image writes on OutOfMemoryError, with the size of
byte[] and char[] arrays by bucket and the count of a few classes that are per request.   perf/hprof-histogram.py DUMP [TOP]"""
import mmap, struct, sys, collections
path = sys.argv[1]; top = int(sys.argv[2]) if len(sys.argv) > 2 else 25
f = open(path, "rb"); mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
hdr_end = mm.find(b"\0") + 1
idsz = struct.unpack_from(">I", mm, hdr_end)[0]; pos = hdr_end + 12
IDF = ">Q" if idsz == 8 else ">I"
def rid(p): return struct.unpack_from(IDF, mm, p)[0]
TYPE_SIZE = {4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
TYPE_NAME = {4: "boolean[]", 5: "char[]", 6: "float[]", 7: "double[]", 8: "byte[]", 9: "short[]", 10: "int[]", 11: "long[]"}
utf8 = {}; classname = {}; inst_size = {}
count = collections.Counter(); size = collections.Counter(); buckets = collections.defaultdict(lambda: [0,0])
n = len(mm)
while pos < n:
    tag = mm[pos]; length = struct.unpack_from(">I", mm, pos + 5)[0]; body = pos + 9
    if tag == 0x01:
        utf8[rid(body)] = bytes(mm[body + idsz: body + length])
    elif tag == 0x02:
        cid = rid(body + 4); nid = rid(body + 4 + idsz + 4); classname[cid] = nid
    elif tag in (0x0C, 0x1C):
        p = body; end = body + length
        while p < end:
            sub = mm[p]; p += 1
            if sub == 0x21:
                cid = rid(p + idsz + 4); nb = struct.unpack_from(">I", mm, p + idsz + 4 + idsz)[0]
                count[cid] += 1; size[cid] += nb + 16; p += idsz + 4 + idsz + 4 + nb
            elif sub == 0x22:
                ne = struct.unpack_from(">I", mm, p + idsz + 4)[0]; cid = rid(p + idsz + 8)
                count[cid] += 1; size[cid] += 16 + ne * idsz; p += idsz + 8 + idsz + ne * idsz
            elif sub == 0x23:
                ne = struct.unpack_from(">I", mm, p + idsz + 4)[0]; t = mm[p + idsz + 8]
                k = TYPE_NAME[t]; b = (1<<max(0,(ne*TYPE_SIZE[t]-1).bit_length())) if ne else 0; buckets[(k,b)][0]+=1; buckets[(k,b)][1]+=ne*TYPE_SIZE[t]; count[k] += 1; size[k] += 16 + ne * TYPE_SIZE[t]; p += idsz + 9 + ne * TYPE_SIZE[t]
            elif sub == 0x20:
                p += idsz + 4 + 6 * idsz + 4
                cp = struct.unpack_from(">H", mm, p)[0]; p += 2
                for _ in range(cp):
                    t = mm[p + 2]; p += 3 + (idsz if t == 2 else TYPE_SIZE[t])
                sc = struct.unpack_from(">H", mm, p)[0]; p += 2
                for _ in range(sc):
                    t = mm[p + idsz]; p += idsz + 1 + (idsz if t == 2 else TYPE_SIZE[t])
                ic = struct.unpack_from(">H", mm, p)[0]; p += 2 + ic * (idsz + 1)
            elif sub in (0xFF, 0x05, 0x07): p += idsz
            elif sub in (0x01,): p += 2 * idsz
            elif sub in (0x02, 0x03, 0x08): p += idsz + 8
            elif sub in (0x04, 0x06): p += idsz + 4
            else: raise SystemExit(f"unknown sub-record {sub:#x} at {p}")
    pos = body + length
def name(k):
    if isinstance(k, str): return k
    return utf8.get(classname.get(k), b"?").decode().replace("/", ".")
tot = sum(size.values())
print(f"{sum(count.values()):,} objects, {tot/1e6:.0f} MB shallow")
print(f"{'MB':>7} {'count':>10}  class")
for k, s in size.most_common(top): print(f"{s/1e6:7.1f} {count[k]:10,}  {name(k)}")

for arr in ("byte[]","char[]"):
    print(f"-- {arr} by size class (upper bound of bytes): count, total MB")
    rows=sorted(((b,v) for (k,b),v in buckets.items() if k==arr), key=lambda x:-x[1][1])[:6]
    for b,(c,t) in rows: print(f"   <= {b:>8,} B: {c:8,}  {t/1e6:7.1f} MB")
want=("jdk.internal.vm.StackChunk","com.oracle.svm.core.heap.StoredContinuation","java.lang.VirtualThread","java.lang.ThreadLocal$ThreadLocalMap","com.zaxxer.hikari.pool.PoolEntry","java.util.concurrent.ForkJoinTask","org.apache.tomcat.util.net.SocketProcessorBase","org.apache.tomcat.util.net.NioEndpoint$SocketProcessor","io.micrometer.observation.SimpleObservation","io.micrometer.observation.SimpleObservation$SimpleScope","org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter","org.apache.catalina.connector.Request","org.apache.tomcat.util.buf.MessageBytes")
print("-- selected classes: count, shallow MB")
byname={name(k):(count[k],size[k]) for k in size if not isinstance(k,str)}
for w in want:
    if w in byname: print(f"   {byname[w][0]:8,} {byname[w][1]/1e6:7.1f}  {w}")
