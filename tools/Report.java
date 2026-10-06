// Reads what the load tests and the profiling runs leave behind, and says it in words: a Markdown table comparing variants, a summary
// of the garbage collections in an application log, and the class histogram of a heap dump.
//
//   tools/run Report summary RESULTS_DIR       the output of perf/bench.sh for several variants, one subdirectory each, as a table
//   tools/run Report gc APP_LOG                how often a native image collects, how long it pauses and whether the live heap grows
//                                              (needs the log of a run with -XX:+PrintGC)
//   tools/run Report hprof DUMP [TOP]          the classes of an HPROF heap dump, such as the one a native image writes on
//                                              OutOfMemoryError, by shallow size, with byte[] and char[] by size class and the count of
//                                              a few classes that exist once per request
//   tools/run Report profile OUT_DIR           one line for a run of perf/profile.sh: requests a second, failures, OutOfMemoryErrors
//   tools/run Report server PROMETHEUS END DUR what Prometheus saw of the application over the DUR seconds that ended at END (epoch
//                                              seconds), as the JSON object perf/bench.sh records under "server"
//   tools/run Report json FILE                 says whether FILE is JSON, and exits 1 if it is not
//
// They replace perf/report.py, gc-summary.py and hprof-histogram.py. The JSON is read with the reader of DpopClient.java, which sits
// beside this file as a link. Numbers are written with a point whatever the language of the machine.
//
// A compact source file for JDK 25. Exit status: see Cli.run.

import module java.net.http;

private static final String USAGE = """
        usage: tools/run Report summary RESULTS_DIR
               tools/run Report gc APP_LOG
               tools/run Report hprof DUMP [TOP]
               tools/run Report profile OUT_DIR
               tools/run Report server PROMETHEUS_URL END_EPOCH_SECONDS DURATION_SECONDS
               tools/run Report json FILE""";

private static final Locale POINT = Locale.ROOT;

void main(String[] args) {
    Cli.run("Report", USAGE, () -> {
        if (args.length == 0) {
            throw new Cli.Usage("a command is needed");
        }
        switch (args[0]) {
            case "summary" -> summary(args);
            case "gc" -> gc(args);
            case "hprof" -> hprof(args);
            case "profile" -> profile(args);
            case "server" -> server(args);
            case "json" -> json(args);
            default -> throw new Cli.Usage("unknown command " + args[0]);
        }
    });
}

private static String f(String format, Object... values) {
    return String.format(POINT, format, values);
}

/**
 * A number with [decimals] places, rounded as Python did when these reports were written: the exact value of the double, and a
 * tie to the even digit. `%.1f` rounds a tie up, which moved a figure in a published table by one.
 */
private static String d(double value, int decimals) {
    return new BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
}

// ---- summary ----------------------------------------------------------------------------------------------------------

@SuppressWarnings("unchecked")
private static Map<String, Object> object(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
}

@SuppressWarnings("unchecked")
private static List<Object> array(Object value) {
    return value instanceof List<?> list ? (List<Object>) list : List.of();
}

private static double number(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0;
}

private static DpopClient.Json parse(Path file) throws IOException {
    try {
        return DpopClient.Json.parse(Cli.read(file));
    } catch (RuntimeException notJson) {
        if (notJson instanceof Cli.Failure failure) {
            throw failure;
        }
        throw new Cli.Failure(file + " is not valid JSON: " + notJson.getMessage());
    }
}

private static String ms(Object seconds) {
    return seconds instanceof Number number ? d(number.doubleValue() * 1000, 1) : "n/a";
}

private static double peakMemory(Path file) throws IOException {
    var pattern = Pattern.compile("(\\d+(?:\\.\\d+)?)(MiB|GiB)\\s*/");
    double peak = 0;
    for (var line : Files.readAllLines(file)) {
        var match = pattern.matcher(line);
        if (match.find()) {
            peak = Math.max(peak, Double.parseDouble(match.group(1)) * (match.group(2).equals("GiB") ? 1024 : 1));
        }
    }
    return peak;
}

private static double k6(Path file, String metric, String field) throws IOException {
    return number(object(object(parse(file).get("metrics")).get(metric)).get(field));
}

private static void summary(String[] args) throws IOException {
    if (args.length != 2) {
        throw new Cli.Usage("summary takes a results directory");
    }
    List<Path> variants;
    try (var children = Files.list(Path.of(args[1]))) {
        variants = children.filter(directory -> Files.exists(directory.resolve("summary.json"))).sorted().toList();
    }
    if (variants.isEmpty()) {
        throw new Cli.Failure(args[1] + " has no subdirectory with a summary.json");
    }
    var summaries = new LinkedHashMap<Path, DpopClient.Json>();
    for (var variant : variants) {
        summaries.put(variant, parse(variant.resolve("summary.json")));
    }
    var names = variants.stream().map(variant -> variant.getFileName().toString()).toList();
    var rows = new ArrayList<List<String>>();
    var started = Pattern.compile("Started \\S+ in ");

    IO.println("| | " + String.join(" | ", names) + " |");
    IO.println("|---|" + "---|".repeat(variants.size()));
    rows.add(row("Time until ready (ms, from `docker run`)", summaries.values().stream().map(s -> String.valueOf((long) number(s.get("ready_ms")))).toList()));
    rows.add(row("Spring startup", summaries.values().stream().map(s -> started.matcher(String.valueOf(s.get("started"))).replaceAll("")).toList()));
    rows.add(row("Memory when idle", summaries.values().stream().map(s -> String.valueOf(s.get("idle_mem"))).toList()));
    var peaks = new ArrayList<String>();
    for (var variant : variants) {
        peaks.add(d(peakMemory(variant.resolve("docker-stats.txt")), 0));
    }
    rows.add(row("Peak memory under load (MiB)", peaks));

    var firstRuns = array(summaries.get(variants.getFirst()).get("runs"));
    for (var firstRun : firstRuns) {
        var run = object(firstRun);
        String name = String.valueOf(run.get("name"));
        rows.add(row("**%s req/s for %s**".formatted(trimmed(run.get("rate")), run.get("duration")), Collections.nCopies(variants.size(), "")));
        var achieved = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var redirect = new ArrayList<String>();
        var create = new ArrayList<String>();
        var seen = new ArrayList<String>();
        var pool = new ArrayList<String>();
        for (var variant : variants) {
            var own = array(summaries.get(variant).get("runs")).stream().map(candidate -> object(candidate)).filter(candidate -> name.equals(String.valueOf(candidate.get("name")))).findFirst()
                    .orElseThrow(() -> new Cli.Failure(variant + " has no run " + name));
            var server = object(own.get("server"));
            var file = variant.resolve(name + ".k6.json");
            achieved.add(d(k6(file, "http_reqs", "rate"), 0));
            failed.add(d(k6(file, "http_req_failed", "value") * 100, 2) + "%");
            redirect.add("%s / %s / %s".formatted(ms(server.get("redirect_p50")), ms(server.get("redirect_p95")), ms(server.get("redirect_p99"))));
            create.add("%s / %s".formatted(ms(server.get("create_p50")), ms(server.get("create_p99"))));
            seen.add(d(k6(file, "redirect_latency", "p(95)"), 1));
            pool.add("%s / %s".formatted(ms(server.get("hikari_acquire_max")), d(number(server.get("hikari_timeouts")), 0)));
        }
        rows.add(row("achieved req/s", achieved));
        rows.add(row("failed requests", failed));
        rows.add(row("redirect p50 / p95 / p99 (ms, server)", redirect));
        rows.add(row("create p50 / p99 (ms, server)", create));
        rows.add(row("redirect p95 (ms, as k6 saw it)", seen));
        rows.add(row("pool acquire max (ms) / timeouts", pool));
    }
    for (var row : rows) {
        IO.println("| " + row.getFirst() + " | " + String.join(" | ", row.subList(1, row.size())) + " |");
    }
}

private static List<String> row(String label, List<String> cells) {
    var row = new ArrayList<String>();
    row.add(label);
    row.addAll(cells);
    return row;
}

/** A number as a person writes it: 1500 and not 1500.0. */
private static String trimmed(Object value) {
    return value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue()) ? String.valueOf((long) number.doubleValue()) : String.valueOf(value);
}

// ---- gc ---------------------------------------------------------------------------------------------------------------

private record Collection(double at, String kind, String cause, double before, double after, double pauseMs) {
}

private static final Pattern GC_LINE = Pattern.compile("\\[([\\d.]+)s\\] GC\\(\\d+\\) Pause (Incremental GC|Full GC) \\(([^)]*)\\) ([\\d.]+)M->([\\d.]+)M ([\\d.]+)ms");

private static void gc(String[] args) throws IOException {
    if (args.length != 2) {
        throw new Cli.Usage("gc takes an application log");
    }
    var all = new ArrayList<Collection>();
    for (var line : Files.readAllLines(Path.of(args[1]))) {
        var match = GC_LINE.matcher(line);
        if (match.find()) {
            all.add(new Collection(Double.parseDouble(match.group(1)), match.group(2), match.group(3), Double.parseDouble(match.group(4)), Double.parseDouble(match.group(5)), Double.parseDouble(match.group(6))));
        }
    }
    if (all.size() < 2) {
        throw new Cli.Failure(args[1] + " has fewer than two GC lines: run the application with -XX:+PrintGC");
    }
    double seconds = all.getLast().at() - all.getFirst().at();
    if (seconds <= 0) {
        throw new Cli.Failure(args[1] + " has GC lines that span no time");
    }
    var incremental = all.stream().filter(c -> c.kind().equals("Incremental GC")).toList();
    var full = all.stream().filter(c -> c.kind().equals("Full GC")).toList();
    double pauses = all.stream().mapToDouble(Collection::pauseMs).sum() / 1000;
    IO.println("%d collections in %ss: %d incremental (%s/s), %d full (%s/s)".formatted(all.size(), d(seconds, 0), incremental.size(), d(incremental.size() / seconds, 1), full.size(), d(full.size() / seconds, 2)));
    IO.println("total pause %ss = %s%% of time; max pause %sms".formatted(d(pauses, 1), d(pauses / seconds * 100, 1), d(all.stream().mapToDouble(Collection::pauseMs).max().orElse(0), 0)));
    summarize("incremental", incremental);
    summarize("full", full);
    var causes = new LinkedHashMap<String, Integer>();
    all.forEach(c -> causes.merge(c.kind() + " (" + c.cause() + ")", 1, Integer::sum));
    IO.println("causes: " + causes.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
}

private static void summarize(String name, List<Collection> kind) {
    if (kind.isEmpty()) {
        return;
    }
    int quarter = Math.max(kind.size() / 4, 1);
    double first = kind.subList(0, quarter).stream().mapToDouble(Collection::after).average().orElse(0);
    double last = kind.subList(kind.size() - quarter, kind.size()).stream().mapToDouble(Collection::after).average().orElse(0);
    IO.println("%s: live after GC first quarter avg %sMB, last quarter avg %sMB, max %sMB; avg pause %sms".formatted(name, d(first, 1), d(last, 1),
            d(kind.stream().mapToDouble(Collection::after).max().orElse(0), 1), d(kind.stream().mapToDouble(Collection::pauseMs).average().orElse(0), 1)));
}

// ---- hprof ------------------------------------------------------------------------------------------------------------

private static final Map<Integer, Integer> TYPE_SIZE = Map.of(4, 1, 5, 2, 6, 4, 7, 8, 8, 1, 9, 2, 10, 4, 11, 8);

private static final Map<Integer, String> TYPE_NAME = Map.of(4, "boolean[]", 5, "char[]", 6, "float[]", 7, "double[]", 8, "byte[]", 9, "short[]", 10, "int[]", 11, "long[]");

private static final List<String> PER_REQUEST = List.of("jdk.internal.vm.StackChunk", "com.oracle.svm.core.heap.StoredContinuation", "java.lang.VirtualThread",
        "java.lang.ThreadLocal$ThreadLocalMap", "com.zaxxer.hikari.pool.PoolEntry", "java.util.concurrent.ForkJoinTask", "org.apache.tomcat.util.net.SocketProcessorBase",
        "org.apache.tomcat.util.net.NioEndpoint$SocketProcessor", "io.micrometer.observation.SimpleObservation", "io.micrometer.observation.SimpleObservation$SimpleScope",
        "org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter", "org.apache.catalina.connector.Request", "org.apache.tomcat.util.buf.MessageBytes");

private static void hprof(String[] args) throws IOException {
    if (args.length < 2 || args.length > 3) {
        throw new Cli.Usage("hprof takes a heap dump and optionally how many classes to show");
    }
    int top = args.length == 3 ? Integer.parseInt(args[2]) : 25;
    ByteBuffer dump;
    try (var channel = FileChannel.open(Path.of(args[1]), StandardOpenOption.READ)) {
        if (channel.size() > Integer.MAX_VALUE) {
            throw new Cli.Failure(args[1] + " is larger than 2 GB, which this reader does not map");
        }
        dump = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    }
    int headerEnd = 0;
    while (dump.get(headerEnd) != 0) {
        headerEnd++;
    }
    headerEnd++;
    int idSize = dump.getInt(headerEnd);
    int position = headerEnd + 12;
    if (idSize != 4 && idSize != 8) {
        throw new Cli.Failure(args[1] + " does not look like an HPROF dump: identifiers of " + idSize + " bytes");
    }

    var utf8 = new HashMap<Long, String>();
    var classNames = new HashMap<Long, Long>();
    var count = new LinkedHashMap<Object, Long>();
    var size = new LinkedHashMap<Object, Long>();
    var buckets = new HashMap<String, long[]>();
    while (position < dump.limit()) {
        int tag = dump.get(position) & 0xFF;
        long length = Integer.toUnsignedLong(dump.getInt(position + 5));
        int body = position + 9;
        if (tag == 0x01) {
            byte[] text = new byte[(int) length - idSize];
            dump.get(body + idSize, text);
            utf8.put(id(dump, body, idSize), new String(text, StandardCharsets.UTF_8));
        } else if (tag == 0x02) {
            classNames.put(id(dump, body + 4, idSize), id(dump, body + 4 + idSize + 4, idSize));
        } else if (tag == 0x0C || tag == 0x1C) {
            int p = body;
            int end = (int) (body + length);
            while (p < end) {
                int sub = dump.get(p++) & 0xFF;
                switch (sub) {
                    case 0x21 -> {
                        long classId = id(dump, p + idSize + 4, idSize);
                        int bytes = dump.getInt(p + idSize + 4 + idSize);
                        add(count, size, classId, bytes + 16L);
                        p += idSize + 4 + idSize + 4 + bytes;
                    }
                    case 0x22 -> {
                        int elements = dump.getInt(p + idSize + 4);
                        long classId = id(dump, p + idSize + 8, idSize);
                        add(count, size, classId, 16L + (long) elements * idSize);
                        p += idSize + 8 + idSize + elements * idSize;
                    }
                    case 0x23 -> {
                        int elements = dump.getInt(p + idSize + 4);
                        int type = dump.get(p + idSize + 8);
                        long bytes = (long) elements * TYPE_SIZE.get(type);
                        String kind = TYPE_NAME.get(type);
                        long bucket = elements == 0 ? 0 : 1L << Math.max(0, 64 - Long.numberOfLeadingZeros(bytes - 1));
                        var entry = buckets.computeIfAbsent(kind + "/" + bucket, key -> new long[2]);
                        entry[0]++;
                        entry[1] += bytes;
                        add(count, size, kind, 16 + bytes);
                        p += idSize + 9 + (int) bytes;
                    }
                    case 0x20 -> p = skipClassDump(dump, p, idSize);
                    case 0xFF, 0x05, 0x07 -> p += idSize;
                    case 0x01 -> p += 2 * idSize;
                    case 0x02, 0x03, 0x08 -> p += idSize + 8;
                    case 0x04, 0x06 -> p += idSize + 4;
                    default -> throw new Cli.Failure("unknown sub-record 0x%x at %d".formatted(sub, p));
                }
            }
        }
        position = (int) (body + length);
    }

    java.util.function.Function<Object, String> name = key -> key instanceof String text ? text
            : utf8.getOrDefault(classNames.get((Long) key), "?").replace('/', '.');
    long total = size.values().stream().mapToLong(Long::longValue).sum();
    IO.println(f("%,d objects, ", count.values().stream().mapToLong(Long::longValue).sum()) + d(total / 1e6, 0) + " MB shallow");
    IO.println(f("%7s %10s  class", "MB", "count"));
    size.entrySet().stream().sorted(Map.Entry.<Object, Long>comparingByValue().reversed()).limit(top)
            .forEach(entry -> IO.println(f("%7s %,10d  %s", d(entry.getValue() / 1e6, 1), count.get(entry.getKey()), name.apply(entry.getKey()))));

    for (String array : List.of("byte[]", "char[]")) {
        IO.println("-- " + array + " by size class (upper bound of bytes): count, total MB");
        buckets.entrySet().stream().filter(entry -> entry.getKey().startsWith(array + "/"))
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(6)
                .forEach(entry -> IO.println(f("   <= %,8d B: %,8d  %7s MB", Long.parseLong(entry.getKey().substring(array.length() + 1)), entry.getValue()[0], d(entry.getValue()[1] / 1e6, 1))));
    }
    IO.println("-- selected classes: count, shallow MB");
    var byName = new HashMap<String, Long[]>();
    size.forEach((key, bytes) -> {
        if (!(key instanceof String)) {
            byName.put(name.apply(key), new Long[]{count.get(key), bytes});
        }
    });
    for (String wanted : PER_REQUEST) {
        var found = byName.get(wanted);
        if (found != null) {
            IO.println(f("   %,8d %7s  %s", found[0], d(found[1] / 1e6, 1), wanted));
        }
    }
}

private static long id(ByteBuffer dump, int at, int idSize) {
    return idSize == 8 ? dump.getLong(at) : Integer.toUnsignedLong(dump.getInt(at));
}

private static void add(Map<Object, Long> count, Map<Object, Long> size, Object key, long bytes) {
    count.merge(key, 1L, Long::sum);
    size.merge(key, bytes, Long::sum);
}

/** Past a class dump, whose size depends on its constant pool and its fields. */
private static int skipClassDump(ByteBuffer dump, int start, int idSize) {
    int p = start + idSize + 4 + 6 * idSize + 4;
    int constants = dump.getShort(p) & 0xFFFF;
    p += 2;
    for (int i = 0; i < constants; i++) {
        int type = dump.get(p + 2);
        p += 3 + (type == 2 ? idSize : TYPE_SIZE.get(type));
    }
    int statics = dump.getShort(p) & 0xFFFF;
    p += 2;
    for (int i = 0; i < statics; i++) {
        int type = dump.get(p + idSize);
        p += idSize + 1 + (type == 2 ? idSize : TYPE_SIZE.get(type));
    }
    int fields = dump.getShort(p) & 0xFFFF;
    return p + 2 + fields * (idSize + 1);
}

// ---- profile, server, json --------------------------------------------------------------------------------------------

private static void profile(String[] args) throws IOException {
    if (args.length != 2) {
        throw new Cli.Usage("profile takes the directory of a run");
    }
    Path directory = Path.of(args[1]).toAbsolutePath().normalize();
    var metrics = object(parse(directory.resolve("k6.json")).get("metrics"));
    long outOfMemory = Pattern.compile("OutOfMemoryError").matcher(Cli.read(directory.resolve("app.log"))).results().count();
    IO.println("%s: achieved %s rps, failed %s%%, OOM lines %d".formatted(directory.getFileName(), d(number(object(metrics.get("http_reqs")).get("rate")), 0),
            d(number(object(metrics.get("http_req_failed")).get("value")) * 100, 2), outOfMemory));
}

private static void json(String[] args) throws IOException {
    if (args.length != 2) {
        throw new Cli.Usage("json takes a file");
    }
    parse(Path.of(args[1]));
}

private static void server(String[] args) throws IOException {
    if (args.length != 4) {
        throw new Cli.Usage("server takes the address of Prometheus, an end and a duration");
    }
    String base = args[1].replaceAll("/+$", "");
    long at = Long.parseLong(args[2]) + 7;
    String window = args[3] + "s";
    String redirect = "uri=\"/{shortCode}\"";
    String create = "uri=\"/api/short-links\",method=\"POST\"";
    var queries = new LinkedHashMap<String, String>();
    queries.put("redirect_p50", quantile(0.5, redirect, window));
    queries.put("redirect_p95", quantile(0.95, redirect, window));
    queries.put("redirect_p99", quantile(0.99, redirect, window));
    queries.put("create_p50", quantile(0.5, create, window));
    queries.put("create_p99", quantile(0.99, create, window));
    queries.put("hikari_acquire_max", "max_over_time(hikaricp_connections_acquire_seconds_max[" + window + "])");
    queries.put("hikari_timeouts", "increase(hikaricp_connections_timeout_total[" + window + "])");
    queries.put("gc_pause_seconds", "sum(increase(jvm_gc_pause_seconds_sum[" + window + "]))");
    queries.put("process_cpu_avg", "avg_over_time(process_cpu_usage[" + window + "])");

    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var answer = new LinkedHashMap<String, Object>();
    for (var query : queries.entrySet()) {
        answer.put(query.getKey(), instant(http, base, query.getValue(), at));
    }
    IO.println(DpopClient.Json.write(answer));
}

private static String quantile(double q, String matcher, String window) {
    return "histogram_quantile(%s, sum by (le) (increase(http_server_requests_seconds_bucket{%s}[%s])))".formatted(q, matcher, window);
}

/** The value of an instant query, or null when Prometheus has no series for it or the value is not a number. */
private static Double instant(HttpClient http, String base, String query, long at) throws IOException {
    var form = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&time=" + at;
    var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/query")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
    try {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Cli.Failure("Prometheus answered " + response.statusCode() + " to " + query);
        }
        var results = array(object(DpopClient.Json.parse(response.body()).get("data")).get("result"));
        if (results.isEmpty()) {
            return null;
        }
        var value = array(object(results.getFirst()).get("value"));
        double parsed = value.size() == 2 ? Double.parseDouble(String.valueOf(value.get(1))) : Double.NaN;
        return Double.isFinite(parsed) ? parsed : null;
    } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new Cli.Failure("interrupted while asking Prometheus");
    }
}
