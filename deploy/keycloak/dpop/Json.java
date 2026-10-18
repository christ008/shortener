package dpop;

import module java.base;

/**
 * A strict JSON reader into maps, lists, strings, numbers, booleans and null, and a writer for the same, with no library. A
 * response that is not JSON is an error that names it.
 */
class Json {
    private final Map<String, Object> members;

    private Json(Map<String, Object> members) {
        this.members = members;
    }

    static Json parse(String text) {
        Object value = new Reader(text).readDocument();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> members = new LinkedHashMap<>();
            map.forEach((name, member) -> members.put(String.valueOf(name), member));
            return new Json(members);
        }
        throw new Failure("expected a JSON object, got: " + Http.excerpt(text, 200));
    }

    /** A member that is a string, or null when it is missing or of another type. */
    String string(String name) {
        return members.get(name) instanceof String value ? value : null;
    }

    Object get(String name) {
        return members.get(name);
    }

    @Override
    public String toString() {
        return write(members);
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeMembers(out, map);
        } else if (value instanceof List<?> list) {
            writeItems(out, list);
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass());
        }
    }

    private static void writeMembers(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            write(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeItems(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            write(out, list.get(i));
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append("\\u%04x".formatted((int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        Object readDocument() {
            Object value = readValue();
            skipWhitespace();
            if (position != text.length()) throw error("unexpected text after the value");
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (position >= text.length()) throw error("unexpected end");
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> members = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek('}')) {
                position++;
                return members;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) throw error("expected a member name");
                String name = readString();
                skipWhitespace();
                expect(':');
                members.put(name, readValue());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                } else {
                    expect('}');
                    return members;
                }
            }
        }

        private List<Object> readArray() {
            List<Object> items = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek(']')) {
                position++;
                return items;
            }
            while (true) {
                items.add(readValue());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                } else {
                    expect(']');
                    return items;
                }
            }
        }

        private String readString() {
            StringBuilder out = new StringBuilder();
            position++;
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("a control character in a string");
                if (c == '\\') {
                    if (position >= text.length()) throw error("an unterminated string");
                    appendEscape(out, text.charAt(position++));
                } else {
                    out.append(c);
                }
            }
            throw error("an unterminated string");
        }

        private void appendEscape(StringBuilder out, char escaped) {
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) throw error("a short \\u escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    } catch (NumberFormatException _) {
                        throw error("a bad \\u escape");
                    }
                    position += 4;
                }
                default -> throw error("a bad escape \\" + escaped);
            }
        }

        private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

        private Object readNumber() {
            Matcher matcher = NUMBER.matcher(text).region(position, text.length());
            if (!matcher.lookingAt()) throw error("unexpected character '" + text.charAt(position) + "'");
            position = matcher.end();
            String number = matcher.group();
            if (matcher.group(2) == null && matcher.group(3) == null) return new BigInteger(number);
            return Double.valueOf(number);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) throw error("unexpected character '" + text.charAt(position) + "'");
            position += word.length();
            return value;
        }

        private boolean peek(char c) {
            return position < text.length() && text.charAt(position) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw error("expected '" + c + "'");
            position++;
        }

        private void skipWhitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
        }

        private Failure error(String what) {
            return new Failure("not JSON (" + what + " at " + position + "): " + Http.excerpt(text, 200));
        }
    }
}
