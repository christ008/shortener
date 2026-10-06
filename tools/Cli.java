import module java.base;

/**
 * What the tools of this directory share, so that none of them has its own copy.
 *
 * - [#run] runs a tool and turns what goes wrong into an exit status and one line on stderr: 0 when it did what was asked,
 *   1 when it could not ([Failure]), 2 for arguments it does not understand ([Usage]). A stack trace is a bug in the tool.
 * - [#jsonString] says a text as the inside of a JSON string, [#read] and [#writeAtomically] read and write files whole.
 *
 * The tools run on JDK 25, as `java tools/Realms.java`, and the launcher finds this file next to the one it starts
 * (JEP 458). Gradle starts them with the toolchain's JDK, see gradle/tooling.gradle.kts. The Java is the plain kind that
 * https://javaevolved.dev/ recommends: `Files.readString`, records, switch expressions, `var`, text blocks.
 */
final class Cli {

    private Cli() {
    }

    interface Action {
        void run() throws IOException;
    }

    static void run(String tool, String usage, Action action) {
        try {
            action.run();
        } catch (Usage usageError) {
            System.err.println(tool + ": " + usageError.getMessage());
            System.err.println(usage);
            System.exit(2);
        } catch (Failure | IOException failure) {
            System.err.println(tool + ": " + failure.getMessage());
            System.exit(1);
        }
    }

    static String read(Path file) throws IOException {
        if (!Files.isReadable(file)) {
            throw new Failure("cannot read " + file);
        }
        return Files.readString(file);
    }

    /** Writes the whole of [content] to [output] or leaves what was there, and gives the file the permissions asked. */
    static void writeAtomically(Path output, String content, String permissions) throws IOException {
        Path next = Files.createTempFile(output.toAbsolutePath().getParent(), output.getFileName().toString(), ".next");
        try {
            Files.writeString(next, content);
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString(permissions));
            Files.move(next, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(next);
        }
    }

    static String jsonString(String text) {
        var out = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
            }
        }
        return out.toString();
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Usage(String message) {
            super(message);
        }
    }
}
