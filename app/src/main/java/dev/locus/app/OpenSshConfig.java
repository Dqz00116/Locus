package dev.locus.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Imports literal connection fields from one OpenSSH client config supplied by the user. */
final class OpenSshConfig {
    static final int MAX_BYTES = 64 * 1024;

    private OpenSshConfig() {}

    static final class Entry {
        final String alias;
        final String hostName;
        final int port;
        final String username;
        final boolean requiresProxy;

        private Entry(String alias, String hostName, int port, String username, boolean requiresProxy) {
            this.alias = alias;
            this.hostName = hostName;
            this.port = port;
            this.username = username;
            this.requiresProxy = requiresProxy;
        }
    }

    static List<Entry> parse(byte[] utf8) throws IOException {
        if (utf8.length == 0 || utf8.length > MAX_BYTES) {
            throw new IOException("Choose a nonempty OpenSSH config up to 64 KiB.");
        }
        String config;
        try {
            config = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("The OpenSSH config must be UTF-8.");
        }
        if (config.startsWith("\ufeff")) config = config.substring(1);
        List<Block> blocks = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Block current = new Block(Collections.emptyList());
        blocks.add(current);
        String[] lines = config.split("\r\n|\n|\r");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int start = skipWhitespace(line, 0);
            int end = start;
            while (end < line.length()) {
                char character = line.charAt(end);
                if (isWhitespace(character) || character == '=' || character == '#') break;
                end++;
            }
            String key = line.substring(start, end).toLowerCase(Locale.ROOT);
            switch (key) {
                case "host":
                    List<String> patterns = arguments(line, end, index + 1);
                    if (patterns.isEmpty()) throw syntaxError(index + 1);
                    current = new Block(patterns);
                    blocks.add(current);
                    for (String pattern : patterns) {
                        if (literalHost(pattern) && current.appliesTo(pattern)) {
                            aliases.putIfAbsent(pattern.toLowerCase(Locale.ROOT), pattern);
                        }
                    }
                    break;
                case "match":
                    // Match conditions can depend on local state or execute commands.
                    current = null;
                    break;
                case "hostname":
                case "port":
                case "user":
                    if (current != null) {
                        List<String> values = arguments(line, end, index + 1);
                        if (values.size() != 1) throw syntaxError(index + 1);
                        current.setFirst(key, values.get(0), index + 1);
                    }
                    break;
                case "proxyjump":
                case "proxycommand":
                    if (current != null && current.proxy == null) {
                        current.proxy = requiresProxy(line, end, index + 1);
                    }
                    break;
                default:
                    // Do not interpret directives such as Include or IdentityFile.
                    break;
            }
        }
        if (aliases.isEmpty()) {
            throw new IOException("The OpenSSH config has no explicit Host aliases.");
        }
        List<Entry> entries = new ArrayList<>(aliases.size());
        for (String alias : aliases.values()) {
            String host = null;
            String user = null;
            int port = 0;
            Boolean proxy = null;
            for (Block block : blocks) {
                if (!block.appliesTo(alias)) continue;
                if (host == null) host = block.host;
                if (user == null) user = block.user;
                if (port == 0) port = block.port;
                if (proxy == null) proxy = block.proxy;
            }
            entries.add(new Entry(alias, host == null ? alias : host, port == 0 ? 22 : port, user,
                    Boolean.TRUE.equals(proxy)));
        }
        return entries;
    }

    private static boolean requiresProxy(String line, int from, int lineNumber) {
        try {
            List<String> values = arguments(line, from, lineNumber);
            return values.size() != 1 || !values.get(0).equalsIgnoreCase("none");
        } catch (IOException invalid) {
            return true;
        }
    }

    private static List<String> arguments(String line, int from, int lineNumber) throws IOException {
        List<String> values = new ArrayList<>();
        int position = skipWhitespace(line, from);
        if (position < line.length() && line.charAt(position) == '=') {
            position = skipWhitespace(line, position + 1);
        }
        StringBuilder value = null;
        char quote = 0;
        for (; position < line.length(); position++) {
            char character = line.charAt(position);
            if (quote == 0 && character == '#') break;
            if (quote == 0 && isWhitespace(character)) {
                if (value != null) values.add(value.toString());
                value = null;
                continue;
            }
            if (value == null) value = new StringBuilder();
            if (character == '\\' && position + 1 < line.length()) {
                value.append(line.charAt(++position));
            } else if (quote == character) {
                quote = 0;
            } else if (quote == 0 && (character == '"' || character == '\'')) {
                quote = character;
            } else {
                value.append(character);
            }
        }
        if (quote != 0) throw syntaxError(lineNumber);
        if (value != null) values.add(value.toString());
        return values;
    }

    private static int skipWhitespace(String value, int from) {
        while (from < value.length() && isWhitespace(value.charAt(from))) from++;
        return from;
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private static boolean literalHost(String value) {
        if (value.isEmpty() || value.length() > 253) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 'a' || character > 'z') && (character < 'A' || character > 'Z')
                    && (character < '0' || character > '9') && "._-:[]".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean literalUser(String value) {
        return !value.isEmpty() && value.length() <= 255 && value.codePoints().noneMatch(character ->
                Character.isWhitespace(character) || Character.isSpaceChar(character)
                        || Character.isISOControl(character) || Character.getType(character) == Character.FORMAT);
    }

    private static IOException syntaxError(int lineNumber) {
        return new IOException("Unsupported OpenSSH connection value on line " + lineNumber + ".");
    }

    private static final class Block {
        private final List<String> patterns;
        private String host;
        private int port;
        private String user;
        private Boolean proxy;

        Block(List<String> patterns) {
            this.patterns = patterns;
        }

        void setFirst(String key, String value, int lineNumber) throws IOException {
            switch (key) {
                case "hostname":
                    if (!literalHost(value)) throw syntaxError(lineNumber);
                    if (host == null) host = value;
                    break;
                case "user":
                    if (!literalUser(value)) throw syntaxError(lineNumber);
                    if (user == null) user = value;
                    break;
                case "port":
                    if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                        throw syntaxError(lineNumber);
                    }
                    int parsed;
                    try {
                        parsed = Integer.parseInt(value);
                    } catch (NumberFormatException invalid) {
                        throw syntaxError(lineNumber);
                    }
                    if (parsed < 1 || parsed > 65535) throw syntaxError(lineNumber);
                    if (port == 0) port = parsed;
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected OpenSSH connection field.");
            }
        }

        boolean appliesTo(String alias) {
            if (patterns.isEmpty()) return true;
            boolean matched = false;
            String name = alias.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                String expression = pattern.toLowerCase(Locale.ROOT);
                boolean negated = expression.startsWith("!");
                if (negated) expression = expression.substring(1);
                if (!matches(name, expression)) continue;
                if (negated) return false;
                matched = true;
            }
            return matched;
        }
    }

    private static boolean matches(String name, String pattern) {
        int position = 0;
        int token = 0;
        int star = -1;
        int consumedByStar = 0;
        while (position < name.length()) {
            if (token < pattern.length() && (pattern.charAt(token) == '?'
                    || pattern.charAt(token) == name.charAt(position))) {
                token++;
                position++;
            } else if (token < pattern.length() && pattern.charAt(token) == '*') {
                star = token++;
                consumedByStar = position;
            } else if (star >= 0) {
                token = star + 1;
                position = ++consumedByStar;
            } else {
                return false;
            }
        }
        while (token < pattern.length() && pattern.charAt(token) == '*') token++;
        return token == pattern.length();
    }
}
