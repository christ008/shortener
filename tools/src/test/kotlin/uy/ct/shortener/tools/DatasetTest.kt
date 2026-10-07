package uy.ct.shortener.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What makes the dataset worth using is that its codes are distinct without being checked, that it is the same on every run for
 * a seed, and that a bigger one starts with a smaller one. The permutation is proven over whole small spaces, including ones
 * that are not a power of two (where the cycle walk is what keeps it a bijection), and the tool over a file it writes.
 */
class DatasetTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a permutation of a small space visits every value exactly once`() {
        listOf(2L, 3L, 1_000L, 4_096L, 238_328L).forEach { space ->
            val permutation = CodePermutation(7, space)
            val seen = BooleanArray(space.toInt())
            for (index in 0 until space) {
                val value = permutation.valueAt(index)
                assertThat(value).isBetween(0, space - 1)
                assertThat(seen[value.toInt()]).describedAs("value $value of $space repeated").isFalse()
                seen[value.toInt()] = true
            }
        }
    }

    @Test
    fun `the first values of the space of generated codes are distinct and inside it`() {
        val permutation = CodePermutation(1)
        val values = (0L until 500_000).map { permutation.valueAt(it) }
        assertThat(values).doesNotHaveDuplicates()
        assertThat(values).allMatch { it in 0 until CodePermutation.GENERATED_SPACE }
        assertThat(permutation.valueAt(CodePermutation.GENERATED_SPACE - 1)).isBetween(0, CodePermutation.GENERATED_SPACE - 1)
    }

    @Test
    fun `a seed gives one sequence and another seed gives another`() {
        val first = (0L until 1_000).map { CodePermutation(1).valueAt(it) }
        assertThat((0L until 1_000).map { CodePermutation(1).valueAt(it) }).isEqualTo(first)
        assertThat((0L until 1_000).map { CodePermutation(2).valueAt(it) }).isNotEqualTo(first)
    }

    @Test
    fun `an index outside the space is refused`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { CodePermutation(1, 1_000).valueAt(1_000) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { CodePermutation(1, 1_000).valueAt(-1) }
    }

    @Test
    fun `base62 is the alphabet of short codes, seven characters, and reads back`() {
        assertThat(Base62.ALPHABET).isEqualTo(
            Regex("const val ALPHABET = \"(.*)\"").find(Files.readString(repositoryRoot.resolve("src/main/kotlin/uy/ct/shortener/shortlink/ShortCode.kt")))!!.groupValues[1],
        )
        assertThat(Base62.encode(0)).isEqualTo("AAAAAAA")
        assertThat(Base62.encode(61)).isEqualTo("AAAAAA9")
        assertThat(Base62.encode(62)).isEqualTo("AAAAABA")
        assertThat(Base62.encode(CodePermutation.GENERATED_SPACE - 1)).isEqualTo("9999999")
        listOf(0L, 1L, 61L, 62L, 123_456_789L, CodePermutation.GENERATED_SPACE - 1).forEach {
            assertThat(Base62.decode(Base62.encode(it))).isEqualTo(it)
        }
    }

    @Test
    fun `writes one distinct code a line and says what it did`() {
        val output = run(Dataset, "100k", "--out", "codes.txt", "--verify", "--threads", "3", root = directory)

        assertThat(output.status).isZero()
        assertThat(output.stderr).isEmpty()
        assertThat(output.stdout).contains("100,000 codes of 7 base62 characters", "100%", "generated 100,000 codes", "0 duplicates", "first codes:")
        val codes = Files.readAllLines(directory.resolve("codes.txt"))
        assertThat(codes).hasSize(100_000).doesNotHaveDuplicates().allMatch { Regex("[A-Za-z0-9]{7}").matches(it) }
    }

    @Test
    fun `the file does not depend on the threads, and a bigger dataset starts with the smaller one`() {
        run(Dataset, "70000", "--out", "one.txt", "--threads", "1", root = directory)
        run(Dataset, "70000", "--out", "many.txt", "--threads", "8", root = directory)
        run(Dataset, "1000", "--out", "small.txt", root = directory)

        assertThat(Files.readString(directory.resolve("many.txt"))).isEqualTo(Files.readString(directory.resolve("one.txt")))
        assertThat(Files.readString(directory.resolve("one.txt"))).startsWith(Files.readString(directory.resolve("small.txt")))
    }

    @Test
    fun `without a file it keeps nothing`() {
        val output = run(Dataset, "1K", root = directory)

        assertThat(output.status).isZero()
        assertThat(output.stdout).contains("nothing is written")
        assertThat(Files.list(directory).use { it.count() }).isZero()
    }

    @Test
    fun `arguments it does not understand are status 2 with the usage`() {
        listOf(
            arrayOf(),
            arrayOf("0"),
            arrayOf("ten"),
            arrayOf("1M", "2M"),
            arrayOf("1M", "--seed"),
            arrayOf("1M", "--seed", "x"),
            arrayOf("1M", "--threads", "0"),
            arrayOf("1M", "--nope"),
            arrayOf("4000B"),
            arrayOf("300M", "--verify"),
        ).forEach { arguments ->
            val output = run(Dataset, *arguments, root = directory)
            assertThat(output.status).describedAs(arguments.joinToString(" ")).isEqualTo(2)
            assertThat(output.stderr).contains("usage: tools/run Dataset")
        }
    }
}
