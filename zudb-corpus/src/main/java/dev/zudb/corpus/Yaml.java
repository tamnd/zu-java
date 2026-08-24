package dev.zudb.corpus;

import static dev.zudb.corpus.Text.quote;
import static dev.zudb.corpus.Text.refuse;

import java.util.ArrayList;
import java.util.List;

/**
 * The subset of YAML the corpus is written in.
 *
 * <p>YAML is a large language and the corpus needs a small corner of it:
 * block mappings, block sequences, and scalars. Everything else is refused
 * with a line number. The files are hand written and are read by people in
 * nine repositories who did not write them, so a construct a reader
 * quietly reinterpreted would be a case that says one thing to a reviewer
 * and another to the runner.
 *
 * <p>So: two space indentation and no tabs, {@code "- "} with exactly one
 * space, plain, single quoted and double quoted scalars on one line, and
 * comments. No flow collections, no block scalars, no anchors, no aliases,
 * no tags, no document markers, no multi document streams.
 *
 * <p>This is the fifth implementation of that subset, after
 * {@code crates/zu-corpus/src/yaml.rs} in the engine, {@code
 * conformance/c/yaml.c} beside it, {@code conformance/reader.py} in
 * zu-python and {@code corpus/reader.go} in zu-go. There are YAML
 * libraries for the JVM that would read these files, and would read a good
 * deal more besides: they would take a flow sequence, a block scalar and
 * an anchor, none of which a case may use. What the corpus needs is a
 * reader that refuses, and the cheapest way to have one is to write it.
 *
 * <p>Whether a scalar was quoted survives parsing, because the value
 * encoding turns on it.
 */
public final class Yaml {

  private Yaml() {}

  /** A vertical tab, which Java has no escape for and Go writes as \v. */
  private static final char VTAB = 0x0B;

  /** What comes off the end of a line, which is every space Go's TrimRight took. */
  private static final String TRAILING = " \r" + VTAB + "\f";

  /**
   * A document, read.
   *
   * @param text the whole file
   * @return the node the document is
   * @throws CorpusException on the first thing in it this reader will not
   *     read
   */
  public static Node parse(String text) {
    List<Line> lines = lex(text);
    if (lines.isEmpty()) {
      throw refuse("the file has nothing in it");
    }
    if (lines.get(0).indent() != 0) {
      throw refuse("line %d: the first line is indented", lines.get(0).no());
    }
    Cursor at = new Cursor(lines);
    Node node = parseNode(at, 0);
    if (at.i < lines.size()) {
      throw refuse("line %d: this belongs to nothing above it", lines.get(at.i).no());
    }
    return node;
  }

  /**
   * One meaningful line: its indent, whether a {@code "- "} opened it, what
   * is left after that, and where it was.
   */
  private record Line(int indent, boolean dash, String text, int no) {}

  /** Where the parser is, which the recursive calls share. */
  private static final class Cursor {
    private final List<Line> lines;
    private int i;

    Cursor(List<Line> lines) {
      this.lines = lines;
    }

    /**
     * The line the cursor is on plus an offset, or null past the end.
     */
    Line at(int offset) {
      int j = i + offset;
      return j >= lines.size() ? null : lines.get(j);
    }
  }

  /**
   * Lines, with blanks and comments dropped and every {@code "- "} split
   * into the item it opens and the content that followed it on the same
   * line.
   *
   * <p>Splitting here rather than in the parser is what lets
   * {@code "- name: x"} and a {@code "name: x"} on its own line be the same
   * shape by the time anything looks at them.
   */
  private static List<Line> lex(String text) {
    List<Line> out = new ArrayList<>();
    String[] raws = text.split("\n", -1);
    for (int n = 0; n < raws.length; n++) {
      String raw = raws[n];
      int no = n + 1;
      int tab = raw.indexOf('\t');
      if (tab >= 0) {
        throw refuse("line %d: a tab at column %d, and indentation here is spaces", no, tab + 1);
      }
      String content = trimRight(stripComment(raw), TRAILING);
      String rest = trimLeft(content);
      int indent = content.length() - rest.length();
      if (rest.isEmpty()) {
        continue;
      }
      if (rest.equals("---") || rest.equals("...")) {
        throw refuse("line %d: %s opens or closes a document, and a file here holds one",
            no, quote(rest));
      }
      if (indent % 2 != 0) {
        throw refuse("line %d: indented %d, and indentation here goes two spaces at a time",
            no, indent);
      }

      if (!rest.equals("-") && !rest.startsWith("- ")) {
        out.add(new Line(indent, false, rest, no));
        continue;
      }
      rest = rest.substring(1);
      if (rest.startsWith("  ")) {
        throw refuse("line %d: a `- ` takes exactly one space, so that what follows it "
            + "lines up with the lines under it", no);
      }
      rest = trimLeft(rest);
      if (rest.startsWith("- ")) {
        throw refuse("line %d: a sequence opening straight into another one, which "
            + "nothing here needs", no);
      }
      out.add(new Line(indent, true, null, no));
      if (!rest.isEmpty()) {
        out.add(new Line(indent + 2, false, rest, no));
      }
    }
    return out;
  }

  /**
   * Everything from an unquoted {@code " #"} on, dropped.
   *
   * <p>Three rules keep this from eating content. A {@code #} starts a
   * comment only with whitespace before it, because one inside a word is
   * part of the word. A quote opens a quoted run only with whitespace
   * before it, because a quote inside a word is part of the word too, which
   * is what lets a {@code doc:} say "it's" without opening a run that never
   * closes. And a quote that opens nothing that closes was not a run at
   * all, which is what lets a {@code query:} hold
   * {@code cast('  42  ' AS INT64)}.
   */
  private static String stripComment(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean opens = i == 0 || space(text.charAt(i - 1));
      if (c == '#' && opens) {
        return text.substring(0, i);
      }
      if ((c == '"' || c == '\'') && opens) {
        int end = closingQuote(text.substring(i + 1), c);
        if (end >= 0) {
          i += 1 + end;
        }
      }
    }
    return text;
  }

  /**
   * Whether a character is one of the ones that can stand before a comment
   * or a quote.
   */
  private static boolean space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == VTAB || c == '\f';
  }

  /**
   * The offset of the quote that closes a run whose opening quote has
   * already been passed, or -1 when the line ends first.
   *
   * <p>The two styles hide a quote differently: a double quoted run escapes
   * with a backslash, and a single quoted run doubles the quote, which is
   * the only escape it has.
   */
  private static int closingQuote(String rest, char mark) {
    for (int i = 0; i < rest.length(); i++) {
      if (rest.charAt(i) == '\\' && mark == '"') {
        i++;
        continue;
      }
      if (rest.charAt(i) == mark) {
        if (mark == '\'' && i + 1 < rest.length() && rest.charAt(i + 1) == '\'') {
          i++;
          continue;
        }
        return i;
      }
    }
    return -1;
  }

  /**
   * The node that starts where the cursor is and is indented {@code
   * indent}, leaving the cursor on the first line that is not part of it.
   */
  private static Node parseNode(Cursor at, int indent) {
    Line here = at.at(0);
    if (here.dash()) {
      return parseSeq(at, indent);
    }
    // A mapping key is a bare word and a ":". Anything else at this
    // position is a scalar standing on its own, which is what the items of
    // a sequence of scalars are.
    if (splitKey(here.text()) != null) {
      return parseMap(at, indent);
    }
    at.i++;
    return parseScalar(here.text(), here.no());
  }

  private static Node parseSeq(Cursor at, int indent) {
    int start = at.at(0).no();
    List<Node> items = new ArrayList<>();
    while (true) {
      Line here = at.at(0);
      if (here == null || !here.dash() || here.indent() != indent) {
        break;
      }
      int opened = here.no();
      at.i++;
      Line next = at.at(0);
      if (next != null && next.indent() == indent + 2) {
        items.add(parseNode(at, indent + 2));
      } else if (next != null && next.indent() > indent) {
        throw refuse("line %d: indented %d, where an item of the sequence on line %d "
            + "is indented %d", next.no(), next.indent(), opened, indent + 2);
      } else {
        throw refuse("line %d: a `-` with nothing after it", opened);
      }
    }
    return Node.seq(start, items);
  }

  private static Node parseMap(Cursor at, int indent) {
    int start = at.at(0).no();
    List<Node.Pair> pairs = new ArrayList<>();
    while (true) {
      Line here = at.at(0);
      if (here == null || here.dash() || here.indent() != indent) {
        break;
      }
      Key split = splitKey(here.text());
      if (split == null) {
        break;
      }
      int opened = here.no();
      at.i++;

      Node value;
      Line next = at.at(0);
      if (!split.rest().isEmpty()) {
        value = parseScalar(split.rest(), opened);
      } else if (next != null && next.indent() == indent + 2) {
        value = parseNode(at, indent + 2);
      } else if (next != null && next.indent() > indent) {
        throw refuse("line %d: indented %d, where what is under `%s:` on line %d is indented %d",
            next.no(), next.indent(), split.key(), opened, indent + 2);
      } else {
        value = Node.empty(opened);
      }
      for (Node.Pair p : pairs) {
        if (p.key().equals(split.key())) {
          throw refuse("line %d: %s is set twice in one mapping", opened, split.key());
        }
      }
      pairs.add(new Node.Pair(split.key(), value));
    }
    return Node.map(start, pairs);
  }

  /** A key and the rest of the line that followed it. */
  private record Key(String key, String rest) {}

  /**
   * The key and the rest of the line, when the line opens a mapping entry,
   * or null when it does not.
   *
   * <p>A key is a bare word, and the {@code ":"} after it ends the line or
   * has a space after it, so that a plain scalar holding a colon is still a
   * scalar.
   */
  private static Key splitKey(String text) {
    String key;
    String rest;
    int cut = text.indexOf(": ");
    if (cut >= 0) {
      key = text.substring(0, cut);
      rest = trimLeft(text.substring(cut + 2));
    } else {
      if (!text.endsWith(":")) {
        return null;
      }
      key = text.substring(0, text.length() - 1);
      rest = "";
    }
    if (key.isEmpty()) {
      return null;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      boolean bare = c == '_' || c == '-'
          || ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9');
      if (!bare) {
        return null;
      }
    }
    return new Key(key, rest);
  }

  private static Node parseScalar(String text, int at) {
    for (char mark : new char[] {'"', '\''}) {
      if (text.isEmpty() || text.charAt(0) != mark) {
        continue;
      }
      String body = text.substring(1);
      // The closing quote is found by scanning rather than by taking the
      // last one on the line, so that `"a" and "b"` is refused instead of
      // read as one scalar with quotes in the middle.
      int end = closingQuote(body, mark);
      if (end < 0) {
        throw refuse("line %d: a %c that opens and does not close on its line", at, mark);
      }
      if (end + 1 != body.length()) {
        throw refuse("line %d: %s after the scalar ends", at, quote(body.substring(end + 1)));
      }
      String inner = body.substring(0, end);
      if (mark == '\'') {
        // A single quoted run has one escape, the doubled quote, and a
        // backslash in it is a backslash.
        return Node.scalar(at, inner.replace("''", "'"), true);
      }
      return Node.scalar(at, unescape(inner, at), true);
    }
    if (!text.isEmpty() && "[]{}&*!|>%@`".indexOf(text.charAt(0)) >= 0) {
      throw refuse("line %d: a plain scalar opening with '%c', which is a construct this "
          + "reader does not read", at, text.charAt(0));
    }
    return Node.scalar(at, text, false);
  }

  /**
   * The escape a backslash and this character spell, or -1 for one the
   * corpus does not use.
   *
   * <p>The escapes that name a code point by its digits are not here,
   * because the corpus writes those as the character itself and a case that
   * wants the digits is testing the engine's own escapes inside a query
   * rather than the file's.
   */
  private static int escape(char c) {
    switch (c) {
      case '"':
        return '"';
      case '\\':
        return '\\';
      case 'n':
        return '\n';
      case 'r':
        return '\r';
      case 't':
        return '\t';
      case '0':
        return 0;
      case 'b':
        return '\b';
      case 'f':
        return '\f';
      default:
        return -1;
    }
  }

  private static String unescape(String body, int at) {
    StringBuilder out = new StringBuilder(body.length());
    for (int i = 0; i < body.length(); i++) {
      if (body.charAt(i) != '\\') {
        out.append(body.charAt(i));
        continue;
      }
      if (i + 1 >= body.length()) {
        throw refuse("line %d: a scalar ending in a backslash", at);
      }
      char next = body.charAt(i + 1);
      int c = escape(next);
      if (c < 0) {
        throw refuse("line %d: \\%c is not an escape", at, next);
      }
      out.append((char) c);
      i++;
    }
    return out.toString();
  }

  /** The text with any of {@code cut} taken off the end. */
  private static String trimRight(String text, String cut) {
    int end = text.length();
    while (end > 0 && cut.indexOf(text.charAt(end - 1)) >= 0) {
      end--;
    }
    return text.substring(0, end);
  }

  /** The text with spaces taken off the front. */
  private static String trimLeft(String text) {
    int start = 0;
    while (start < text.length() && text.charAt(start) == ' ') {
      start++;
    }
    return text.substring(start);
  }
}
