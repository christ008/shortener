package uy.ct.shortener.tools

import uy.ct.shortener.tools.Report.d
import uy.ct.shortener.tools.Report.f
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

/**
 * `Report hprof`: the classes of an HPROF heap dump by shallow size, such as the dump a native image writes on
 * OutOfMemoryError, with byte[] and char[] by size class and the count of a few classes that exist once per request.
 *
 * The file is mapped and read in place, which is why it must be under 2 GB. A record that is not one of those the format
 * defines is a failure that says where, because skipping it would read the rest of the file out of step.
 */
internal object HeapHistogram {

    private val TYPE_SIZE = mapOf(4 to 1, 5 to 2, 6 to 4, 7 to 8, 8 to 1, 9 to 2, 10 to 4, 11 to 8)

    private val TYPE_NAME = mapOf(4 to "boolean[]", 5 to "char[]", 6 to "float[]", 7 to "double[]", 8 to "byte[]", 9 to "short[]", 10 to "int[]", 11 to "long[]")

    private val PER_REQUEST = listOf(
        "jdk.internal.vm.StackChunk", "com.oracle.svm.core.heap.StoredContinuation", "java.lang.VirtualThread",
        "java.lang.ThreadLocal\$ThreadLocalMap", "com.zaxxer.hikari.pool.PoolEntry", "java.util.concurrent.ForkJoinTask",
        "org.apache.tomcat.util.net.SocketProcessorBase", "org.apache.tomcat.util.net.NioEndpoint\$SocketProcessor",
        "io.micrometer.observation.SimpleObservation", "io.micrometer.observation.SimpleObservation\$SimpleScope",
        "org.springframework.security.web.ObservationFilterChainDecorator\$ObservationFilter", "org.apache.catalina.connector.Request",
        "org.apache.tomcat.util.buf.MessageBytes",
    )

    /** How many of each kind of object, and how many bytes they take, by class id or by the name of a primitive array. */
    private class Tally {
        val count = LinkedHashMap<Any, Long>()
        val size = LinkedHashMap<Any, Long>()

        fun add(key: Any, bytes: Long) {
            count.merge(key, 1L, Long::plus)
            size.merge(key, bytes, Long::plus)
        }
    }

    fun print(arguments: List<String>, context: Context) {
        if (arguments.size !in 2..3) throw Usage("hprof takes a heap dump and optionally how many classes to show")
        val top = if (arguments.size == 3) arguments[2].toIntOrNull() ?: throw Usage("how many classes must be a whole number, found ${arguments[2]}") else 25
        val file = context.path(arguments[1])
        val dump = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            if (channel.size() > Int.MAX_VALUE) throw Failure("${arguments[1]} is larger than 2 GB, which this reader does not map")
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
        val utf8 = HashMap<Long, String>()
        val classNames = HashMap<Long, Long>()
        val tally = Tally()
        val buckets = HashMap<String, LongArray>()
        try {
            var headerEnd = 0
            while (dump.get(headerEnd).toInt() != 0) headerEnd++
            headerEnd++
            val idSize = dump.getInt(headerEnd)
            if (idSize != 4 && idSize != 8) throw Failure("${arguments[1]} does not look like an HPROF dump: identifiers of $idSize bytes")

            var position = headerEnd + 12
            while (position < dump.limit()) {
                val tag = dump.get(position).toInt() and 0xFF
                val length = Integer.toUnsignedLong(dump.getInt(position + 5))
                val body = position + 9
                if (tag == 0x01) {
                    val text = ByteArray(length.toInt() - idSize)
                    dump.get(body + idSize, text)
                    utf8[id(dump, body, idSize)] = String(text, StandardCharsets.UTF_8)
                } else if (tag == 0x02) {
                    classNames[id(dump, body + 4, idSize)] = id(dump, body + 4 + idSize + 4, idSize)
                } else if (tag == 0x0C || tag == 0x1C) {
                    readHeapDump(dump, body, (body + length).toInt(), idSize, tally, buckets)
                }
                position = (body + length).toInt()
            }
        } catch (outside: IndexOutOfBoundsException) {
            throw Failure("${arguments[1]} does not look like a complete HPROF dump: a record runs past the end of the file")
        } catch (unknown: NoSuchElementException) {
            throw Failure("${arguments[1]} does not look like an HPROF dump: an array of a type it does not define")
        } catch (negative: NegativeArraySizeException) {
            throw Failure("${arguments[1]} does not look like an HPROF dump: a record of negative length")
        }

        fun name(key: Any): String = if (key is String) key else (classNames[key]?.let { utf8[it] } ?: "?").replace('/', '.')

        val total = tally.size.values.sum()
        context.out.println(f("%,d objects, ", tally.count.values.sum()) + d(total / 1e6, 0) + " MB shallow")
        context.out.println(f("%7s %10s  class", "MB", "count"))
        tally.size.entries.sortedByDescending { it.value }.take(top)
            .forEach { context.out.println(f("%7s %,10d  %s", d(it.value / 1e6, 1), tally.count[it.key], name(it.key))) }

        for (array in listOf("byte[]", "char[]")) {
            context.out.println("-- $array by size class (upper bound of bytes): count, total MB")
            buckets.entries.filter { it.key.startsWith("$array/") }.sortedByDescending { it.value[1] }.take(6)
                .forEach {
                    context.out.println(f("   <= %,8d B: %,8d  %7s MB", it.key.substring(array.length + 1).toLong(), it.value[0], d(it.value[1] / 1e6, 1)))
                }
        }
        context.out.println("-- selected classes: count, shallow MB")
        val byName = HashMap<String, Pair<Long, Long>>()
        tally.size.forEach { (key, bytes) -> if (key !is String) byName[name(key)] = tally.count.getValue(key) to bytes }
        for (wanted in PER_REQUEST) {
            val (count, bytes) = byName[wanted] ?: continue
            context.out.println(f("   %,8d %7s  %s", count, d(bytes / 1e6, 1), wanted))
        }
    }

    /** The sub-records of one heap dump record, counting the objects and arrays in it and skipping the rest. */
    private fun readHeapDump(dump: ByteBuffer, start: Int, end: Int, idSize: Int, tally: Tally, buckets: HashMap<String, LongArray>) {
        var p = start
        while (p < end) {
            val sub = dump.get(p++).toInt() and 0xFF
            when (sub) {
                0x21 -> {
                    val classId = id(dump, p + idSize + 4, idSize)
                    val bytes = dump.getInt(p + idSize + 4 + idSize)
                    tally.add(classId, bytes + 16L)
                    p += idSize + 4 + idSize + 4 + bytes
                }
                0x22 -> {
                    val elements = dump.getInt(p + idSize + 4)
                    val classId = id(dump, p + idSize + 8, idSize)
                    tally.add(classId, 16L + elements.toLong() * idSize)
                    p += idSize + 8 + idSize + elements * idSize
                }
                0x23 -> {
                    val elements = dump.getInt(p + idSize + 4)
                    val type = dump.get(p + idSize + 8).toInt()
                    val bytes = elements.toLong() * TYPE_SIZE.getValue(type)
                    val kind = TYPE_NAME.getValue(type)
                    val bucket = if (elements == 0) 0L else 1L shl maxOf(0, 64 - (bytes - 1).countLeadingZeroBits())
                    val entry = buckets.getOrPut("$kind/$bucket") { LongArray(2) }
                    entry[0]++
                    entry[1] += bytes
                    tally.add(kind, 16 + bytes)
                    p += idSize + 9 + bytes.toInt()
                }
                0x20 -> p = skipClassDump(dump, p, idSize)
                0xFF, 0x05, 0x07 -> p += idSize
                0x01 -> p += 2 * idSize
                0x02, 0x03, 0x08 -> p += idSize + 8
                0x04, 0x06 -> p += idSize + 4
                else -> throw Failure("unknown sub-record 0x%x at %d".format(sub, p))
            }
        }
    }

    private fun id(dump: ByteBuffer, at: Int, idSize: Int): Long = if (idSize == 8) dump.getLong(at) else Integer.toUnsignedLong(dump.getInt(at))

    /** Past a class dump, whose size depends on its constant pool and its fields. */
    private fun skipClassDump(dump: ByteBuffer, start: Int, idSize: Int): Int {
        var p = start + idSize + 4 + 6 * idSize + 4
        val constants = dump.getShort(p).toInt() and 0xFFFF
        p += 2
        repeat(constants) {
            val type = dump.get(p + 2).toInt()
            p += 3 + if (type == 2) idSize else TYPE_SIZE.getValue(type)
        }
        val statics = dump.getShort(p).toInt() and 0xFFFF
        p += 2
        repeat(statics) {
            val type = dump.get(p + idSize).toInt()
            p += idSize + 1 + if (type == 2) idSize else TYPE_SIZE.getValue(type)
        }
        val fields = dump.getShort(p).toInt() and 0xFFFF
        return p + 2 + fields * (idSize + 1)
    }
}
