package dev.locus.app;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Adds visual spans to source previews without changing or interpreting their text. */
final class SyntaxHighlight {
    private static final int MAX_SOURCE_LENGTH = 64 * 1024;
    private static final int MAX_SPANS = 2048;

    private enum Token {
        KEYWORD(R.color.syntax_keyword), STRING(R.color.syntax_string),
        COMMENT(R.color.syntax_comment), NUMBER(R.color.syntax_number);

        final int color;

        Token(int color) {
            this.color = color;
        }
    }

    private enum Language {
        C_STYLE("//", true, "'\"",
                "abstract as assert auto bool boolean break byte case catch char class const constexpr continue "
                        + "data default delete do double else enum explicit export extends extern false final finally "
                        + "float for fun goto if implements import instanceof int interface internal is long namespace "
                        + "new noexcept null object operator override package private protected public record return "
                        + "sealed short signed sizeof static string struct super switch synchronized template this throw "
                        + "throws true try typedef typename union unsigned using val var virtual void volatile when while"),
        NESTED_C_STYLE("//", true, "'\"", C_STYLE.keywords),
        JAVASCRIPT("//", true, "'\"`",
                "abstract as async await break case catch class const continue debugger declare default delete do else "
                        + "enum export extends false finally for from function if implements import in instanceof "
                        + "interface let new null of private protected public readonly return static super switch "
                        + "this throw true try type typeof undefined var void while with yield"),
        GO("//", true, "'\"`",
                "break case chan const continue default defer else fallthrough false for func go goto if import "
                        + "interface map nil package range return select struct switch true type var"),
        RUST("//", true, "'\"",
                "as async await break const continue crate dyn else enum extern false fn for if impl in let loop "
                        + "match mod move mut pub ref return self Self static struct super trait true type unsafe "
                        + "use where while"),
        PYTHON("#", false, "'\"",
                "and as assert async await break class continue def del elif else except False finally for from "
                        + "global if import in is lambda None nonlocal not or pass raise return True try while with yield"),
        SHELL("#", false, "'\"`",
                "break case continue declare do done elif else esac export fi for function if in local readonly "
                        + "return select set source then trap unset until while"),
        JSON("", false, "\"",
                "true false null"),
        JSON_WITH_COMMENTS("//", true, "\"", JSON.keywords),
        CONFIG("#", false, "'\"",
                "true false null yes no on off"),
        YAML("#", false, "'\"", CONFIG.keywords),
        INI(";", false, "'\"", CONFIG.keywords),
        SQL("--", true, "'\"`",
                "add all alter and as asc begin between by case check column commit create cross default delete desc "
                        + "distinct drop else end exists false foreign from group having in index inner insert into "
                        + "is join left like limit not null offset on or order outer primary references right "
                        + "rollback select set table then true union unique update values view when where with"),
        CSS("", true, "'\"", "important inherit initial revert unset"),
        MARKUP("", false, "'\"", "");

        final String lineComment;
        final boolean blockComments;
        final String quotes;
        final Set<String> keywords;

        Language(String lineComment, boolean blockComments, String quotes, String keywords) {
            this(lineComment, blockComments, quotes, new HashSet<>(Arrays.asList(keywords.split(" "))));
        }

        Language(String lineComment, boolean blockComments, String quotes, Set<String> keywords) {
            this.lineComment = lineComment;
            this.blockComments = blockComments;
            this.quotes = quotes;
            this.keywords = keywords;
        }
    }

    private SyntaxHighlight() {}

    static CharSequence apply(Context context, String fileName, String source) {
        if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) return source;
        Language language = languageFor(fileName);
        if (language == null) return source;

        Painter painter = new Painter(context, source);
        if (language == Language.MARKUP) {
            highlightMarkup(source, painter);
        } else {
            highlightCode(source, language, painter);
        }
        return painter.result();
    }

    private static Language languageFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "Makefile".equalsIgnoreCase(fileName) ? Language.SHELL : null;
        }
        switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "c":
            case "cc":
            case "cpp":
            case "cxx":
            case "h":
            case "hh":
            case "hpp":
            case "hxx":
            case "cs":
            case "java":
            case "gradle":
                return Language.C_STYLE;
            case "kt":
            case "kts":
            case "swift":
                return Language.NESTED_C_STYLE;
            case "js":
            case "jsx":
            case "mjs":
            case "cjs":
            case "ts":
            case "tsx":
            case "mts":
            case "cts":
                return Language.JAVASCRIPT;
            case "go":
                return Language.GO;
            case "rs":
                return Language.RUST;
            case "py":
            case "pyw":
                return Language.PYTHON;
            case "sh":
            case "bash":
            case "zsh":
            case "fish":
            case "bashrc":
            case "zshrc":
                return Language.SHELL;
            case "json":
                return Language.JSON;
            case "jsonc":
                return Language.JSON_WITH_COMMENTS;
            case "yaml":
            case "yml":
                return Language.YAML;
            case "toml":
            case "env":
            case "conf":
                return Language.CONFIG;
            case "ini":
            case "properties":
                return Language.INI;
            case "sql":
                return Language.SQL;
            case "css":
            case "scss":
                return Language.CSS;
            case "xml":
            case "svg":
            case "html":
            case "htm":
            case "xhtml":
                return Language.MARKUP;
            default:
                return null;
        }
    }

    private static void highlightCode(String source, Language language, Painter painter) {
        int position = 0;
        while (position < source.length() && painter.hasRoom()) {
            char current = source.charAt(position);
            int end;
            if (lineCommentAt(source, position, language)) {
                end = lineEnd(source, position + language.lineComment.length());
                painter.add(Token.COMMENT, position, end);
            } else if (language.blockComments && source.startsWith("/*", position)) {
                end = blockCommentEnd(source, position + 2, language);
                painter.add(Token.COMMENT, position, end);
            } else if (language.quotes.indexOf(current) >= 0) {
                end = stringEnd(source, position, language);
                if (end > position + 1) painter.add(Token.STRING, position, end);
            } else if (Character.isJavaIdentifierStart(source.codePointAt(position))) {
                end = identifierEnd(source, position);
                if (end - position <= 16) {
                    String word = source.substring(position, end);
                    if (language == Language.SQL) word = word.toLowerCase(Locale.ROOT);
                    if (language.keywords.contains(word)) painter.add(Token.KEYWORD, position, end);
                }
            } else if (isDigit(current)) {
                end = numberEnd(source, position);
                painter.add(Token.NUMBER, position, end);
            } else {
                end = position + 1;
            }
            position = end;
        }
    }

    private static boolean lineCommentAt(String source, int position, Language language) {
        if (language == Language.INI && source.charAt(position) == '#') return true;
        if (language.lineComment.isEmpty() || !source.startsWith(language.lineComment, position)) return false;
        if (position == 0 || (language != Language.SHELL && language != Language.YAML)) return true;
        char before = source.charAt(position - 1);
        return Character.isWhitespace(before) || (language == Language.SHELL && ";|&()<>".indexOf(before) >= 0);
    }

    private static int blockCommentEnd(String source, int position, Language language) {
        if (language != Language.RUST && language != Language.NESTED_C_STYLE) return after(source, "*/", position);
        int depth = 1;
        while (position < source.length()) {
            if (source.startsWith("/*", position)) {
                depth++;
                position += 2;
            } else if (source.startsWith("*/", position)) {
                depth--;
                position += 2;
                if (depth == 0) return position;
            } else {
                position++;
            }
        }
        return position;
    }

    private static int stringEnd(String source, int start, Language language) {
        char quote = source.charAt(start);
        if (language == Language.RUST && quote == '\'' && start + 1 < source.length()
                && Character.isJavaIdentifierStart(source.codePointAt(start + 1))) {
            int nameEnd = identifierEnd(source, start + 1);
            if (nameEnd == source.length() || source.charAt(nameEnd) != quote) return start + 1;
        }

        boolean tripleQuotes = language == Language.PYTHON || language == Language.CONFIG
                || ((language == Language.C_STYLE || language == Language.NESTED_C_STYLE) && quote == '"');
        if (tripleQuotes && start + 2 < source.length()
                && source.charAt(start + 1) == quote && source.charAt(start + 2) == quote) {
            return quotedEnd(source, start + 3, source.substring(start, start + 3), true, false, true);
        }
        boolean escape = !(language == Language.SHELL && quote == '\'')
                && !(language == Language.GO && quote == '`');
        boolean multiline = quote == '`' || language == Language.SQL || language == Language.SHELL;
        boolean doubledQuote = language == Language.SQL || (language == Language.YAML && quote == '\'');
        return quotedEnd(source, start + 1, String.valueOf(quote), escape, doubledQuote, multiline);
    }

    private static int quotedEnd(String source, int position, String delimiter, boolean escape,
                                 boolean doubledQuote, boolean multiline) {
        while (position < source.length()) {
            char current = source.charAt(position);
            if (escape && current == '\\' && position + 1 < source.length()) {
                position += source.startsWith("\\\r\n", position) ? 3 : 2;
            } else if (source.startsWith(delimiter, position)) {
                int end = position + delimiter.length();
                if (!doubledQuote || !source.startsWith(delimiter, end)) return end;
                position = end + delimiter.length();
            } else if (!multiline && (current == '\n' || current == '\r')) {
                return position;
            } else {
                position++;
            }
        }
        return position;
    }

    private static int lineEnd(String source, int start) {
        int position = start;
        while (position < source.length() && source.charAt(position) != '\n'
                && source.charAt(position) != '\r') position++;
        return position;
    }

    private static int identifierEnd(String source, int position) {
        while (position < source.length()) {
            int character = source.codePointAt(position);
            if (!Character.isJavaIdentifierPart(character)) break;
            position += Character.charCount(character);
        }
        return position;
    }

    private static int numberEnd(String source, int start) {
        int length = source.length();
        if (source.charAt(start) == '0' && start + 2 < length) {
            int radix = 0;
            switch (source.charAt(start + 1)) {
                case 'x': case 'X': radix = 16; break;
                case 'o': case 'O': radix = 8; break;
                case 'b': case 'B': radix = 2; break;
                default: break;
            }
            if (radix != 0 && Character.digit(source.charAt(start + 2), radix) >= 0) {
                int end = start + 3;
                while (end < length && (Character.digit(source.charAt(end), radix) >= 0
                        || source.charAt(end) == '_')) end++;
                return end;
            }
        }

        int end = decimalDigits(source, start + 1);
        if (end + 1 < length && source.charAt(end) == '.' && isDigit(source.charAt(end + 1))) {
            end = decimalDigits(source, end + 2);
        }
        if (end + 1 < length && (source.charAt(end) == 'e' || source.charAt(end) == 'E')) {
            int exponent = end + 1;
            char sign = source.charAt(exponent);
            if (sign == '+' || sign == '-') exponent++;
            if (exponent < length && isDigit(source.charAt(exponent))) end = decimalDigits(source, exponent + 1);
        }
        return end;
    }

    private static int decimalDigits(String source, int start) {
        int position = start;
        while (position < source.length() && (isDigit(source.charAt(position))
                || source.charAt(position) == '_')) position++;
        return position;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static int after(String source, String delimiter, int start) {
        int index = source.indexOf(delimiter, start);
        return index < 0 ? source.length() : index + delimiter.length();
    }

    private static void highlightMarkup(String source, Painter painter) {
        int position = 0;
        while (painter.hasRoom()) {
            int opening = source.indexOf('<', position);
            if (opening < 0 || opening + 1 >= source.length()) return;
            if (source.startsWith("<!--", opening)) {
                position = after(source, "-->", opening + 4);
                painter.add(Token.COMMENT, opening, position);
            } else if (source.startsWith("<![CDATA[", opening)) {
                position = after(source, "]]>", opening + 9);
            } else {
                int nameStart = opening + 1;
                char first = source.charAt(nameStart);
                if (first == '/' || first == '?' || first == '!') nameStart++;
                if (nameStart < source.length() && Character.isLetter(source.charAt(nameStart))) {
                    position = highlightTag(source, opening, nameStart, painter);
                } else {
                    position = opening + 1;
                }
            }
        }
    }

    private static int highlightTag(String source, int opening, int name, Painter painter) {
        int position = name + 1;
        while (position < source.length()) {
            char current = source.charAt(position);
            if (!Character.isLetterOrDigit(current) && ":_-.".indexOf(current) < 0) break;
            position++;
        }
        painter.add(Token.KEYWORD, opening, position);

        while (position < source.length() && painter.hasRoom()) {
            char current = source.charAt(position);
            if (current == '<') return position;
            if (current == '>') {
                painter.add(Token.KEYWORD, position, position + 1);
                return position + 1;
            }
            if (current == '"' || current == '\'') {
                int end = quotedEnd(source, position + 1, String.valueOf(current), false, false, true);
                painter.add(Token.STRING, position, end);
                position = end;
            } else {
                position++;
            }
        }
        return position;
    }

    private static final class Painter {
        private final String source;
        private final int[] colors = new int[Token.values().length];
        private SpannableString highlighted;
        private int spanCount;

        Painter(Context context, String source) {
            this.source = source;
            for (Token token : Token.values()) colors[token.ordinal()] = context.getColor(token.color);
        }

        boolean hasRoom() {
            return spanCount < MAX_SPANS;
        }

        void add(Token token, int start, int end) {
            if (highlighted == null) highlighted = new SpannableString(source);
            highlighted.setSpan(new ForegroundColorSpan(colors[token.ordinal()]), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spanCount++;
        }

        CharSequence result() {
            return highlighted == null ? source : highlighted;
        }
    }
}
