package dev.zudb.corpus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code {type, value}} encoding a case writes its values in.
 *
 * <p>Every value in the corpus is a mapping with a {@code type} naming the
 * GQL type and a {@code value} holding the payload. The type is written
 * down rather than inferred because the corpus is read by several
 * languages and inference is where they differ: a bare {@code 1} is an
 * integer in YAML, and which integer it becomes is a decision each host
 * language makes on its own.
 *
 * <p>The payload is a YAML scalar where a YAML scalar is exact, and a
 * string where it is not. An integer wider than 53 bits is a string,
 * because most YAML readers hand a number to a double. A float is a
 * string, for that reason and for NaN, inf and -0.0. A temporal value is a
 * string, because YAML has no type that keeps an offset.
 *
 * <p>NODE, EDGE and PATH are the values a graph has and a table does not,
 * and they are written as names rather than as the numbers the engine
 * holds. A node is {@code person#1}, the table it is a row of and which row
 * of it. An edge is {@code knows#0->1}, its table and the two rows it runs
 * between. A path is a sequence, like a list, holding a node and then an
 * edge and a node for each hop.
 *
 * <p>Refusing the wrong form is half the point, and refusing it here is
 * what makes this a second reader of the corpus rather than a consumer of
 * it.
 *
 * <p>One thing this client does not need and the Python one does. There, a
 * temporal written finer than a microsecond is a value the host language
 * cannot hold, and a case carrying one is reported unsupported rather than
 * run. The JVM's day is not a datetime either: a date here is a count of
 * days and everything else is a count of nanoseconds, which is the same
 * resolution the engine keeps, so every temporal in the corpus is a value
 * this client holds exactly.
 */
public final class Values {

  private Values() {}

  /** The vertical tab, which the JVM has no escape for. */
  private static final char VTAB = 0x0B;

  /**
   * Whether a type's payload is written as a quoted string. False is a type
   * a YAML scalar carries without loss, true is one it does not, and absent
   * is not a type at all.
   */
  static final Map<String, Boolean> QUOTED_FORM = quotedForm();

  private static Map<String, Boolean> quotedForm() {
    Map<String, Boolean> out = new LinkedHashMap<>();
    out.put("NULL", false);
    out.put("BOOL", false);
    out.put("INT8", false);
    out.put("INT16", false);
    out.put("INT32", false);
    out.put("INT64", true);
    out.put("UINT8", false);
    out.put("UINT16", false);
    out.put("UINT32", false);
    out.put("UINT64", true);
    out.put("FLOAT32", true);
    out.put("FLOAT64", true);
    out.put("STRING", false);
    // A byte string is written in quotes because its hexits are digits as
    // often as not: a bare 0041 is a number with a leading zero in one
    // reader and the string it looks like in another, and neither of them
    // is the two octets the case meant.
    out.put("BYTES", true);
    out.put("DATE", true);
    out.put("LOCALTIME", true);
    out.put("ZONEDTIME", true);
    out.put("LOCALDATETIME", true);
    out.put("ZONEDDATETIME", true);
    out.put("DURATION", true);
    out.put("LIST", false);
    // A node and an edge are written in quotes because what a case spells
    // is a name and two numbers with punctuation between them, which is
    // text in every reader and a number in none.
    out.put("NODE", true);
    out.put("EDGE", true);
    // A path is a sequence, like a list, because that is what it is: the
    // nodes and edges of a walk, in the order they were walked.
    out.put("PATH", false);
    return Map.copyOf(out);
  }

  /**
   * The types the encoding reserves a name for and the engine has no
   * runtime value for yet, kept apart from an outright typo so that the
   * refusal says which of the two it is.
   */
  private static final List<String> RESERVED = List.of("DECIMAL");

  /**
   * The range each integer width holds, so that a case writing a value its
   * own type cannot carry is refused rather than stored wider than it says.
   *
   * <p>UINT64 stops at the signed maximum because the engine's integer is
   * signed and 64 bits wide, and wrapping the top half into a negative
   * would be a case that passes while meaning the opposite of what it says.
   */
  private static final Map<String, long[]> BOUNDS = Map.of(
      "INT8", new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE},
      "INT16", new long[] {Short.MIN_VALUE, Short.MAX_VALUE},
      "INT32", new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE},
      "INT64", new long[] {Long.MIN_VALUE, Long.MAX_VALUE},
      "UINT8", new long[] {0, 0xFF},
      "UINT16", new long[] {0, 0xFFFF},
      "UINT32", new long[] {0, 0xFFFF_FFFFL},
      "UINT64", new long[] {0, Long.MAX_VALUE});

  /**
   * Whether a type is written quoted, and whether it is a type at all.
   *
   * @param ty the name a case wrote
   * @return true when the payload is quoted, false when it is bare, and
   *     null when this is not a type this encoding knows
   */
  public static Boolean form(String ty) {
    return QUOTED_FORM.get(ty);
  }

  /**
   * Whether a type is one of the eight integer widths, which is what the
   * runner asks before it hands a load column to the method that takes
   * whole numbers.
   *
   * @param ty the name a case wrote
   * @return true when it is
   */
  static boolean integer(String ty) {
    return BOUNDS.containsKey(ty);
  }

  private static String unknownType(String ty) {
    if (RESERVED.contains(ty)) {
      return ty + " is a type the encoding reserves and the engine has no value for";
    }
    return ty + " is not a type this encoding knows";
  }

  /**
   * The value a {@code {type, value}} mapping describes.
   *
   * @param node the mapping
   * @return the value
   * @throws CorpusException if it is not one
   */
  public static Cell decode(Node node) {
    if (node.map() == null) {
      throw Text.refuse("line %d: a value is a mapping of `type` and `value`, and this is %s",
          node.line(), node.what());
    }
    List<String> unknown = node.unknown("type", "value");
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a value has no key %s", node.line(), Text.quote(unknown.get(0)));
    }
    return typed(node);
  }

  /**
   * The type and value of a mapping that carries more than those two, which
   * is a parameter: it is a value with a name, and the name belongs to the
   * case rather than to the encoding.
   *
   * @param node the mapping
   * @return the value
   * @throws CorpusException if the type or the payload is not one
   */
  public static Cell typed(Node node) {
    int at = node.line();
    Node tyNode = node.get("type");
    if (tyNode == null) {
      throw Text.refuse("line %d: a value with no `type`", at);
    }
    String ty = tyNode.text();
    if (ty == null) {
      throw Text.refuse("line %d: a `type` that is not a name", at);
    }

    // Checked here as well as in payload, because a value whose type is not
    // a type and which also has no `value` under it should be told about
    // the type first: that is the mistake, and the missing payload is a
    // consequence of it.
    if (form(ty) == null) {
      throw Text.refuse("line %d: %s", at, unknownType(ty));
    }

    if (ty.equals("NULL")) {
      if (node.get("value") != null) {
        throw Text.refuse("line %d: NULL carries no `value`", at);
      }
      return Cell.NULL;
    }
    Node value = node.get("value");
    if (value == null) {
      throw Text.refuse("line %d: a %s with no `value`", at, ty);
    }
    return payload(ty, value);
  }

  /**
   * The value a payload spells under a type that has already been read.
   *
   * <p>A row of a case names its type beside every value. A column of a
   * load names it once at the top and every value under it is a bare
   * payload, which is the same encoding with the type factored out, so it
   * is the same code reading it.
   *
   * @param ty the type, already known
   * @param value the payload
   * @return the value
   * @throws CorpusException if the payload does not spell one
   */
  public static Cell payload(String ty, Node value) {
    Boolean quoted = form(ty);
    if (quoted == null) {
      throw Text.refuse("line %d: %s", value.line(), unknownType(ty));
    }

    if (ty.equals("LIST") || ty.equals("PATH")) {
      // The empty list is a value worth a case and needs a spelling, which
      // is a "value:" with nothing under it.
      List<Node> items = value.seqOrEmpty();
      if (items == null) {
        throw Text.refuse("line %d: a %s holds a sequence of values, and this is %s",
            value.line(), ty, value.what());
      }
      List<Cell> decoded = new ArrayList<>(items.size());
      for (Node item : items) {
        decoded.add(decode(item));
      }
      if (ty.equals("LIST")) {
        return new Cell.List(List.copyOf(decoded));
      }
      return walk(decoded, value.line());
    }

    String text = value.text();
    if (text == null) {
      throw Text.refuse("line %d: a %s holds one scalar, and this is %s",
          value.line(), ty, value.what());
    }
    boolean wasQuoted = value.quoted();
    int at = value.line();
    // The one rule the whole encoding exists for, checked before the text
    // is looked at, because a value that parses is exactly the case where a
    // silent misread would survive review.
    if (quoted && !wasQuoted) {
      // A node and an edge are quoted for a different reason from the
      // numbers, so they are told a different reason. Both reasons are the
      // same rule: a payload is quoted where a bare one would read as
      // something else in some reader of this file.
      if (ty.equals("NODE") || ty.equals("EDGE")) {
        throw Text.refuse("line %d: %s is written in quotes, because %s is a name and two "
            + "numbers and no reader has a scalar for that", at, ty, text);
      }
      throw Text.refuse("line %d: %s is written in quotes, because a bare %s is a number and "
          + "some reader of this file will round it", at, ty, text);
    }
    if (!quoted && wasQuoted && !ty.equals("STRING")) {
      throw Text.refuse("line %d: %s is written without quotes, so that a reader cannot take it "
          + "for a string", at, ty);
    }

    Cell out = switch (ty) {
      case "NODE" -> nodeAt(text);
      case "EDGE" -> edgeAt(text);
      default -> scalar(ty, text);
    };
    if (out == null) {
      throw Text.refuse("line %d: %s is not a %s", at, Text.quote(text), ty);
    }
    return out;
  }

  /**
   * The nodes and edges of a walk, or what is wrong with the sequence
   * somebody wrote.
   *
   * <p>A path alternates and ends at both ends with a node, so a sequence
   * that does not is a case that could never pass. Refusing it here rather
   * than at the comparison is the difference between a message naming the
   * line and a report saying the row differs.
   */
  private static Cell walk(List<Cell> items, int at) {
    if (items.size() % 2 == 0) {
      throw Text.refuse("line %d: a PATH is a node, then an edge and a node for each hop, so it "
          + "holds an odd number of values and this holds %d", at, items.size());
    }
    for (int i = 0; i < items.size(); i++) {
      boolean wantNode = i % 2 == 0;
      Cell item = items.get(i);
      boolean ok;
      String was;
      if (item instanceof Cell.Node) {
        ok = wantNode;
        was = "a NODE";
      } else if (item instanceof Cell.Edge) {
        ok = !wantNode;
        was = "an EDGE";
      } else {
        ok = false;
        was = "neither a NODE nor an EDGE";
      }
      if (!ok) {
        throw Text.refuse("line %d: a PATH alternates, so value %d is %s where it should be %s",
            at, i + 1, was, wantNode ? "a NODE" : "an EDGE");
      }
    }
    return new Cell.Path(List.copyOf(items));
  }

  /**
   * A node, written as its table and the offset of its row: {@code
   * person#1}.
   *
   * <p>The table's name rather than its id, because the id is a number the
   * file decided and every client builds its own file. Split from the
   * right, so that a table whose name holds a {@code #} is still readable.
   */
  private static Cell nodeAt(String text) {
    int hash = text.lastIndexOf('#');
    if (hash <= 0) {
      return null;
    }
    Long offset = row(text.substring(hash + 1));
    return offset == null ? null : new Cell.Node(text.substring(0, hash), offset);
  }

  /**
   * An edge, written as its table and the rows it runs between: {@code
   * knows#0->1}.
   */
  private static Cell edgeAt(String text) {
    int hash = text.lastIndexOf('#');
    if (hash <= 0) {
      return null;
    }
    String pair = text.substring(hash + 1);
    int arrow = pair.indexOf("->");
    if (arrow < 0) {
      return null;
    }
    Long from = row(pair.substring(0, arrow));
    Long to = row(pair.substring(arrow + 2));
    if (from == null || to == null) {
      return null;
    }
    return new Cell.Edge(text.substring(0, hash), from, to);
  }

  /**
   * A row number, which is one or more ASCII digits and nothing else.
   *
   * <p>Not {@code Long.parseLong}, which takes a leading sign, and not
   * {@code Long.parseUnsignedLong}, which takes one too. Neither is a row
   * number anybody meant to write.
   */
  private static Long row(String text) {
    if (text.isEmpty()) {
      return null;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
    }
    try {
      return Long.parseUnsignedLong(text);
    } catch (NumberFormatException e) {
      // A row number wider than the count of rows any load writes.
      return null;
    }
  }

  /** The value a type's text spells, or null when it spells none. */
  private static Cell scalar(String ty, String text) {
    switch (ty) {
      case "BOOL":
        if (text.equals("true")) {
          return new Cell.Bool(true);
        }
        if (text.equals("false")) {
          return new Cell.Bool(false);
        }
        return null;
      case "STRING":
        return new Cell.Str(text);
      case "BYTES":
        return fromHexits(text);
      case "FLOAT32":
      case "FLOAT64": {
        Double f = parseFloat(text);
        if (f == null) {
          return null;
        }
        return new Cell.Float(ty.equals("FLOAT32") ? (float) (double) f : f);
      }
      case "DATE":
        return time(Temporals.parseDate(text));
      case "LOCALTIME":
        return time(Temporals.parseLocalTime(text));
      case "ZONEDTIME":
        return time(Temporals.parseZonedTime(text));
      case "LOCALDATETIME":
        return time(Temporals.parseLocalDateTime(text));
      case "ZONEDDATETIME":
        return time(Temporals.parseZonedDateTime(text));
      case "DURATION":
        return time(Temporals.parseDuration(text));
      default:
        break;
    }
    long[] range = BOUNDS.get(ty);
    if (range == null) {
      return null;
    }
    long n;
    try {
      n = Long.parseLong(text);
    } catch (NumberFormatException e) {
      return null;
    }
    // Written back out and compared, so that a leading plus, a leading zero
    // and a grouping mark are all refused rather than read as the number
    // they resemble.
    if (!Long.toString(n).equals(text)) {
      return null;
    }
    if (n < range[0] || n > range[1]) {
      return null;
    }
    return new Cell.Int(n);
  }

  private static Cell time(dev.zudb.Value.Temporal value) {
    return value == null ? null : new Cell.Time(value);
  }

  /**
   * A float, including the three spellings YAML has no opinion about. They
   * are spelled the way Rust prints them, because that is what the
   * reference runner writes into a failure report and what a case is pasted
   * from.
   */
  private static Double parseFloat(String text) {
    switch (text) {
      case "NaN":
        return Double.NaN;
      case "inf":
        return Double.POSITIVE_INFINITY;
      case "-inf":
        return Double.NEGATIVE_INFINITY;
      default:
        break;
    }
    // A float is exact here, so `1` is not a FLOAT64 and neither is
    // `1e400`. The first is an integer somebody meant to write as `1.0` and
    // the second is `inf` under another name.
    if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
      return null;
    }
    // Double.parseDouble takes "Infinity", "0x1p-2", a trailing d or f and
    // whitespace at either end, none of which the corpus writes and all of
    // which would be a case that reads differently in the other runners.
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= '0' && c <= '9') {
        continue;
      }
      if (".eE+-".indexOf(c) < 0) {
        return null;
      }
    }
    double f;
    try {
      f = Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return null;
    }
    return Double.isInfinite(f) || Double.isNaN(f) ? null : f;
  }

  /**
   * How a value reads in a failure report, in the encoding's own spelling
   * so that it can be pasted into a case, and line for line what the Rust
   * runner prints so that two reports can be diffed.
   *
   * @param value the value
   * @return the text
   */
  public static String show(Cell value) {
    return switch (value) {
      case Cell.Null _ -> "NULL";
      case Cell.Bool v -> v.value() ? "BOOL true" : "BOOL false";
      case Cell.Int v -> "INT64 \"" + v.value() + "\"";
      case Cell.Float v -> "FLOAT64 \"" + showFloat(v.value()) + "\"";
      case Cell.Str v -> "STRING " + Text.quote(v.value());
      case Cell.Bytes v -> "BYTES \"" + hexits(v.value()) + "\"";
      case Cell.Time v -> showTime(v.value());
      case Cell.List v -> "LIST [" + showAll(v.items()) + "]";
      case Cell.Path v -> "PATH [" + showAll(v.items()) + "]";
      case Cell.Node v -> "NODE \"" + v.table() + "#" + Long.toUnsignedString(v.offset()) + "\"";
      case Cell.Edge v -> "EDGE \"" + v.table() + "#" + Long.toUnsignedString(v.source())
          + "->" + Long.toUnsignedString(v.target()) + "\"";
      case Cell.Record v -> showRecord(v);
      // A graph or a binding table, which is a value no case can write. It
      // prints as itself, under a name that is not a type, so that a report
      // carrying one cannot be mistaken for a case that could be pasted
      // back into the corpus.
      case Cell.Other v -> "(" + v.value().getClass().getSimpleName() + ") " + v.value();
    };
  }

  private static String showTime(dev.zudb.Value.Temporal v) {
    return switch (v.kind()) {
      case DATE -> "DATE \"" + Temporals.showDate(v.count()) + "\"";
      case LOCAL_TIME -> "LOCALTIME \"" + Temporals.showClock(v.count()) + "\"";
      case ZONED_TIME -> "ZONEDTIME \"" + Temporals.showClock(v.count())
          + Temporals.showOffset(v.offsetMinutes()) + "\"";
      case LOCAL_DATETIME -> "LOCALDATETIME \"" + Temporals.showStamp(v.count()) + "\"";
      case ZONED_DATETIME -> "ZONEDDATETIME \""
          + Temporals.showStamp(v.count() + v.offsetMinutes() * Temporals.NANOS_PER_MINUTE)
          + Temporals.showOffset(v.offsetMinutes()) + "\"";
      case DURATION_YEAR_MONTH -> "DURATION \"" + Temporals.showMonths(v.count()) + "\"";
      case DURATION_DAY_TIME -> "DURATION \"" + Temporals.showNanos(v.count()) + "\"";
    };
  }

  /**
   * The fields of a record, in order, so that a report of one reads the
   * same twice. A hash map's order is not stable and a failure that
   * reorders its own fields between runs is a failure nobody can diff.
   */
  private static String showRecord(Cell.Record value) {
    StringBuilder out = new StringBuilder("RECORD {");
    String between = "";
    for (Map.Entry<String, Cell> field : new TreeMap<>(value.fields()).entrySet()) {
      out.append(between).append(field.getKey()).append(": ").append(show(field.getValue()));
      between = ", ";
    }
    return out.append('}').toString();
  }

  private static String showAll(List<Cell> items) {
    StringBuilder out = new StringBuilder();
    String between = "";
    for (Cell item : items) {
      out.append(between).append(show(item));
      between = ", ";
    }
    return out.toString();
  }

  /**
   * A float the way Rust's {@code {:?}} writes one, which is the shortest
   * text that reads back as the same double and always carries a point or
   * an exponent.
   *
   * <p>Written out rather than taken from {@code Double.toString}, which
   * switches to an exponent at a different place, writes the exponent with
   * a capital E and writes a mantissa of one digit as {@code 1.0}. All of
   * those are a report that differs from the reference one without the
   * answer differing, which is the thing this whole file exists to avoid.
   *
   * @param f the double
   * @return the text
   */
  static String showFloat(double f) {
    if (Double.isNaN(f)) {
      return "NaN";
    }
    if (f == Double.POSITIVE_INFINITY) {
      return "inf";
    }
    if (f == Double.NEGATIVE_INFINITY) {
      return "-inf";
    }
    String sign = "";
    double g = f;
    // Not f < 0, which says nothing about the zero that is signed.
    if (Double.doubleToRawLongBits(f) < 0) {
      sign = "-";
      g = -f;
    }
    // The shortest digits that read back as this double, and where the
    // point goes in them. Double.toString has given the shortest since 19,
    // so the digits are taken from it and only the layout is decided here.
    Shortest s = shortest(g);
    String run = s.run();
    int exp = s.exp();
    int point = exp + 1;
    if (point <= -4 || point > 16) {
      String head = run.substring(0, 1);
      if (run.length() > 1) {
        head += "." + run.substring(1);
      }
      return sign + head + "e" + exp;
    }
    if (point <= 0) {
      return sign + "0." + "0".repeat(-point) + run;
    }
    if (point >= run.length()) {
      return sign + run + "0".repeat(point - run.length()) + ".0";
    }
    return sign + run.substring(0, point) + "." + run.substring(point);
  }

  /**
   * The digits that read back as a double, and the power of ten the first
   * of them stands for, so that the value is {@code d.ddd} times ten to it.
   *
   * @param run the digits, with no zero on either end
   * @param exp what the first digit stands for
   */
  private record Shortest(String run, int exp) {}

  /**
   * The shortest text that reads back as a positive double, split into its
   * digits and its exponent.
   *
   * <p>{@code Double.toString} is where the digits come from, since it has
   * given the shortest run that reads back since release 19, give or take
   * the one digit handled below. What it does not give is the exponent,
   * because it writes one only outside a range of its own, so it is worked
   * out here from where the point landed.
   */
  private static Shortest shortest(double g) {
    String text = Double.toString(g);
    int e = text.indexOf('E');
    String head = e < 0 ? text : text.substring(0, e);
    int shift = e < 0 ? 0 : Integer.parseInt(text.substring(e + 1));
    int point = head.indexOf('.');
    String all = head.replace(".", "");
    int start = 0;
    while (start < all.length() - 1 && all.charAt(start) == '0') {
      start++;
    }
    int end = all.length();
    while (end > start + 1 && all.charAt(end - 1) == '0') {
      end--;
    }
    String run = all.substring(start, end);
    int exp = point - start - 1 + shift;
    // Double.toString gives the shortest run that reads back except in one
    // place. Where a single digit would do, its specification asks for the
    // closest decimal of two digits instead, so the smallest subnormal comes
    // out as 4.9e-324 here where every other runner prints 5e-324. Two
    // digits is the only length that can be one too many, so one attempt at
    // shortening closes it, and the round trip is what decides.
    if (run.length() == 2) {
      Shortest one = rounded(run, exp);
      if (Double.parseDouble(one.run() + "e" + one.exp()) == g) {
        return one;
      }
    }
    return new Shortest(run, exp);
  }

  /** Two digits rounded to one, carrying into the exponent at ten. */
  private static Shortest rounded(String run, int exp) {
    int first = run.charAt(0) - '0';
    if (run.charAt(1) >= '5') {
      first++;
    }
    return first == 10 ? new Shortest("1", exp + 1) : new Shortest(String.valueOf(first), exp);
  }

  /**
   * A byte string the way the engine writes one: two hexits to a byte,
   * upper case, no quotes and no X.
   *
   * <p>Upper case because the standard writes the literal that way, and a
   * reader comparing two of these is comparing text, so one case is one
   * answer.
   *
   * @param raw the octets
   * @return the hexits
   */
  static String hexits(byte[] raw) {
    final String to = "0123456789ABCDEF";
    StringBuilder out = new StringBuilder(raw.length * 2);
    for (byte b : raw) {
      out.append(to.charAt((b >> 4) & 0xF)).append(to.charAt(b & 0xF));
    }
    return out.toString();
  }

  /**
   * The bytes a run of hexits names, and null for anything that is not a
   * run of hexits or that names half a byte.
   *
   * <p>Space is allowed anywhere and dropped, which is what the standard's
   * production allows and what lets a long literal be written in groups.
   * Either case reads, because a value that went in as {@code 00ab} and came
   * back as {@code 00AB} is the same value.
   */
  private static Cell fromHexits(String text) {
    byte[] nibbles = new byte[text.length()];
    int n = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == VTAB || c == '\f') {
        continue;
      } else if (c >= '0' && c <= '9') {
        nibbles[n++] = (byte) (c - '0');
      } else if (c >= 'a' && c <= 'f') {
        nibbles[n++] = (byte) (c - 'a' + 10);
      } else if (c >= 'A' && c <= 'F') {
        nibbles[n++] = (byte) (c - 'A' + 10);
      } else {
        return null;
      }
    }
    if (n % 2 != 0) {
      return null;
    }
    // The empty byte string is a value of its own and a case asserts it,
    // which is why this is an array of no octets rather than nothing at
    // all: X'' comes back from the engine as an empty one and the two
    // should read alike.
    byte[] out = new byte[n / 2];
    for (int i = 0; i < n; i += 2) {
      out[i / 2] = (byte) (nibbles[i] << 4 | nibbles[i + 1]);
    }
    return new Cell.Bytes(out);
  }
}
