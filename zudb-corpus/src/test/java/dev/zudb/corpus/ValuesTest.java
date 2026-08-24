package dev.zudb.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Value;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The value encoding, tested on both sides of it.
 *
 * <p>A case says what a statement produces by naming a type and a payload,
 * and the whole point of naming the type is that a payload cannot be
 * misread. So the tests here are mostly refusals: an INT64 written bare is
 * refused because some reader will round it, a STRING written where a LIST
 * belongs is refused, a payload out of its type's range is refused. A round
 * trip through a reader that accepted all three would still look green.
 *
 * <p>The other half is {@link Values#show}, which is what a failure report
 * prints. It is diffed against the Rust runner's line for line, so a float
 * that switches to an exponent one power earlier here than there is a
 * difference in the report that is not a difference in the answer.
 */
class ValuesTest {

  /**
   * The value a {@code type:}/{@code value:} mapping comes to, written the
   * way a case writes it.
   */
  private static Cell value(String text) {
    return Values.decode(Yaml.parse(text));
  }

  /**
   * The message a {@code type:}/{@code value:} mapping was refused with,
   * and a failure when it was read instead.
   */
  private static String declined(String text) {
    return assertThrows(CorpusException.class, () -> Values.decode(Yaml.parse(text)),
        () -> "this should have been refused: " + text).getMessage();
  }

  private static Cell integer(long n) {
    return new Cell.Int(n);
  }

  @Test
  @DisplayName("every type says whether its payload is quoted")
  void everyTypeSaysWhetherItsPayloadIsQuoted() {
    // The whole table, written out rather than iterated over, because the
    // point of the test is that the table is this and not whatever the map
    // happens to hold.
    List<String> bare = List.of("NULL", "BOOL", "INT8", "INT16", "INT32", "UINT8", "UINT16",
        "UINT32", "STRING", "LIST", "PATH");
    List<String> quoted = List.of("INT64", "UINT64", "FLOAT32", "FLOAT64", "BYTES", "DATE",
        "LOCALTIME", "ZONEDTIME", "LOCALDATETIME", "ZONEDDATETIME", "DURATION", "NODE", "EDGE");
    for (String ty : bare) {
      assertEquals(Boolean.FALSE, Values.form(ty), ty + " is written bare");
    }
    for (String ty : quoted) {
      assertEquals(Boolean.TRUE, Values.form(ty), ty + " is written in quotes");
    }
    assertEquals(bare.size() + quoted.size(), Values.QUOTED_FORM.size(),
        "the encoding has as many types as this test names");
    // DECIMAL has a name and no value behind it, and is told apart from a
    // typo so that the message says which of the two happened.
    assertNull(Values.form("DECIMAL"), "the engine has no value for a DECIMAL");
  }

  @Test
  @DisplayName("a payload is read as the type beside it")
  void aPayloadIsReadAsTheTypeBesideIt() {
    record Case(String text, Cell want) {}
    for (Case c : List.of(
        new Case("type: NULL\n", Cell.NULL),
        new Case("type: BOOL\nvalue: true\n", new Cell.Bool(true)),
        new Case("type: BOOL\nvalue: false\n", new Cell.Bool(false)),
        new Case("type: INT8\nvalue: -128\n", integer(-128)),
        new Case("type: INT16\nvalue: 32767\n", integer(32767)),
        new Case("type: INT32\nvalue: -2147483648\n", integer(-2147483648L)),
        new Case("type: INT64\nvalue: \"9223372036854775807\"\n", integer(Long.MAX_VALUE)),
        new Case("type: UINT8\nvalue: 255\n", integer(255)),
        new Case("type: UINT16\nvalue: 65535\n", integer(65535)),
        new Case("type: UINT32\nvalue: 4294967295\n", integer(4294967295L)),
        new Case("type: UINT64\nvalue: \"0\"\n", integer(0)),
        new Case("type: FLOAT64\nvalue: \"1.5\"\n", new Cell.Float(1.5)),
        new Case("type: FLOAT64\nvalue: \"-0.0\"\n", new Cell.Float(-0.0)),
        new Case("type: FLOAT64\nvalue: \"inf\"\n",
            new Cell.Float(Double.POSITIVE_INFINITY)),
        new Case("type: FLOAT64\nvalue: \"-inf\"\n",
            new Cell.Float(Double.NEGATIVE_INFINITY)),
        // A FLOAT32 is held as the double the single rounds to, since that
        // is what comes back out of a column of them.
        new Case("type: FLOAT32\nvalue: \"0.1\"\n", new Cell.Float(0.1f)),
        new Case("type: STRING\nvalue: plain\n", new Cell.Str("plain")),
        new Case("type: STRING\nvalue: ''\n", new Cell.Str("")),
        new Case("type: BYTES\nvalue: \"00AB00\"\n",
            new Cell.Bytes(new byte[] {0, (byte) 0xAB, 0})),
        new Case("type: BYTES\nvalue: \"\"\n", new Cell.Bytes(new byte[0])),
        new Case("type: NODE\nvalue: \"person#1\"\n", new Cell.Node("person", 1)),
        new Case("type: EDGE\nvalue: \"knows#0->2\"\n", new Cell.Edge("knows", 0, 2)))) {
      assertEquals(c.want(), value(c.text()),
          () -> Text.quote(c.text().replace("\n", " ")) + " reads that way");
    }
  }

  /**
   * A STRING is the one type that reads either way, because a string is
   * what a plain scalar already is and a case quotes one only when it has
   * to. Everything else is written one way and refused the other.
   */
  @Test
  @DisplayName("a string reads quoted or bare")
  void aStringReadsQuotedOrBare() {
    assertEquals(new Cell.Str("42"), value("type: STRING\nvalue: 42\n"));
    assertEquals(new Cell.Str("42"), value("type: STRING\nvalue: \"42\"\n"));
  }

  @Test
  @DisplayName("an integer is refused outside the range its type holds")
  void anIntegerIsRefusedOutsideTheRangeItsTypeHolds() {
    record Case(String ty, String text) {}
    for (Case c : List.of(
        new Case("INT8", "128"),
        new Case("INT8", "-129"),
        new Case("INT16", "32768"),
        new Case("INT32", "2147483648"),
        new Case("UINT8", "256"),
        new Case("UINT8", "-1"),
        new Case("UINT16", "65536"),
        new Case("UINT32", "4294967296"))) {
      assertEquals("line 2: " + Text.quote(c.text()) + " is not a " + c.ty(),
          declined("type: " + c.ty() + "\nvalue: " + c.text() + "\n"));
    }
    // UINT64 stops at the signed maximum, because the engine's integer is
    // signed and wrapping the top half into a negative would be a case that
    // passes while meaning the opposite of what it says.
    assertEquals("line 2: \"9223372036854775808\" is not a UINT64",
        declined("type: UINT64\nvalue: \"9223372036854775808\"\n"));
  }

  /**
   * The rule the whole encoding exists for. A bare INT64 is a number some
   * reader in some language rounds, and a bare NODE is a name and two
   * numbers no reader has a scalar for, so the two are told apart.
   */
  @Test
  @DisplayName("a quoted type written bare is refused and says why")
  void aQuotedTypeWrittenBareIsRefusedAndSaysWhy() {
    record Case(String text, String want) {}
    for (Case c : List.of(
        new Case("type: INT64\nvalue: 1\n",
            "line 2: INT64 is written in quotes, because a bare 1 is a number and some reader of "
                + "this file will round it"),
        new Case("type: FLOAT64\nvalue: 1.5\n",
            "line 2: FLOAT64 is written in quotes, because a bare 1.5 is a number and some reader "
                + "of this file will round it"),
        new Case("type: NODE\nvalue: person#1\n",
            "line 2: NODE is written in quotes, because person#1 is a name and two numbers and no "
                + "reader has a scalar for that"),
        new Case("type: EDGE\nvalue: knows#0->1\n",
            "line 2: EDGE is written in quotes, because knows#0->1 is a name and two numbers and "
                + "no reader has a scalar for that"))) {
      assertEquals(c.want(), declined(c.text()));
    }
  }

  @Test
  @DisplayName("a bare type written in quotes is refused")
  void aBareTypeWrittenInQuotesIsRefused() {
    assertEquals(
        "line 2: INT8 is written without quotes, so that a reader cannot take it for a string",
        declined("type: INT8\nvalue: \"1\"\n"));
  }

  @Test
  @DisplayName("a value says what is wrong with it in the order that helps")
  void aValueSaysWhatIsWrongWithItInTheOrderThatHelps() {
    record Case(String what, String text, String want) {}
    for (Case c : List.of(
        new Case("a type nothing knows",
            "type: INTEGER\nvalue: 1\n",
            "line 1: INTEGER is not a type this encoding knows"),
        new Case("a type the encoding holds a name for",
            "type: DECIMAL\nvalue: \"1.0\"\n",
            "line 1: DECIMAL is a type the encoding reserves and the engine has no value for"),
        // The type is the mistake and the missing payload is a consequence
        // of it, so the type is what the message names.
        new Case("a type nothing knows and no payload either",
            "type: INTEGER\n",
            "line 1: INTEGER is not a type this encoding knows"),
        new Case("no type at all",
            "value: 1\n",
            "line 1: a value with no `type`"),
        new Case("a type that is not a name",
            "type:\n  - INT8\nvalue: 1\n",
            "line 1: a `type` that is not a name"),
        new Case("no payload",
            "type: INT8\n",
            "line 1: a INT8 with no `value`"),
        new Case("a payload under NULL",
            "type: NULL\nvalue: 1\n",
            "line 1: NULL carries no `value`"),
        new Case("a key the encoding has no room for",
            "type: INT8\nvalue: 1\nname: n\n",
            "line 1: a value has no key \"name\""),
        new Case("a sequence where a value belongs",
            "- type: INT8\n",
            "line 1: a value is a mapping of `type` and `value`, and this is a sequence"),
        new Case("a scalar where a value belongs",
            "just a scalar\n",
            "line 1: a value is a mapping of `type` and `value`, and this is a scalar"),
        new Case("a sequence under a scalar type",
            "type: INT8\nvalue:\n  - 1\n",
            "line 3: a INT8 holds one scalar, and this is a sequence"),
        new Case("a scalar under LIST",
            "type: LIST\nvalue: 1\n",
            "line 2: a LIST holds a sequence of values, and this is a scalar"))) {
      assertEquals(c.want(), declined(c.text()), c.what());
    }
  }

  @Test
  @DisplayName("a list holds values and the empty one has a spelling")
  void aListHoldsValuesAndTheEmptyOneHasASpelling() {
    Cell got = value("""
        type: LIST
        value:
          - type: INT8
            value: 1
          - type: NULL
          - type: STRING
            value: two
        """);
    assertEquals(new Cell.List(List.of(integer(1), Cell.NULL, new Cell.Str("two"))), got);
    // A "value:" with nothing under it, which is the empty list and a value
    // a case asserts.
    assertEquals(new Cell.List(List.of()), value("type: LIST\nvalue:\n"));
  }

  /**
   * A path alternates and ends at both ends with a node, so a sequence that
   * does not is refused where it is written rather than at the comparison,
   * which is the difference between a message naming a line and a report
   * saying the row differs.
   */
  @Test
  @DisplayName("a path alternates node and edge or is refused")
  void aPathAlternatesNodeAndEdgeOrIsRefused() {
    Cell got = value("""
        type: PATH
        value:
          - type: NODE
            value: "person#0"
          - type: EDGE
            value: "knows#0->1"
          - type: NODE
            value: "person#1"
        """);
    assertEquals(new Cell.Path(List.of(
        new Cell.Node("person", 0),
        new Cell.Edge("knows", 0, 1),
        new Cell.Node("person", 1))), got);

    record Case(String what, String text, String want) {}
    for (Case c : List.of(
        new Case("an even number of values",
            "type: PATH\nvalue:\n  - type: NODE\n    value: \"person#0\"\n"
                + "  - type: EDGE\n    value: \"knows#0->1\"\n",
            "line 3: a PATH is a node, then an edge and a node for each hop, so it holds an odd "
                + "number of values and this holds 2"),
        new Case("an edge where the walk starts",
            "type: PATH\nvalue:\n  - type: EDGE\n    value: \"knows#0->1\"\n",
            "line 3: a PATH alternates, so value 1 is an EDGE where it should be a NODE"),
        new Case("a node in the hop position",
            "type: PATH\nvalue:\n  - type: NODE\n    value: \"person#0\"\n"
                + "  - type: NODE\n    value: \"person#1\"\n  - type: NODE\n"
                + "    value: \"person#2\"\n",
            "line 3: a PATH alternates, so value 2 is a NODE where it should be an EDGE"),
        new Case("something that is neither",
            "type: PATH\nvalue:\n  - type: INT8\n    value: 1\n",
            "line 3: a PATH alternates, so value 1 is neither a NODE nor an EDGE where it should "
                + "be a NODE"))) {
      assertEquals(c.want(), declined(c.text()), c.what());
    }
    // The empty path is refused too, since zero is an even number and a walk
    // with no nodes in it is not a walk.
    assertTrue(declined("type: PATH\nvalue:\n").contains("odd number"),
        "the empty path is not a walk");
  }

  @Test
  @DisplayName("a node and an edge are a table name and row numbers")
  void aNodeAndAnEdgeAreATableNameAndRowNumbers() {
    // Split from the right, so a table whose name holds a # still reads.
    assertEquals(new Cell.Node("od#d", 7), value("type: NODE\nvalue: \"od#d#7\"\n"));
    for (String text : List.of(
        "person", // no offset
        "#1", // no table
        "person#", // no digits
        "person#-1", // a sign, which an unsigned parser would take
        "person#1_0", // a grouping mark, which a parser elsewhere would take too
        "person#a", // not a number
        "person#1->2")) { // an edge under a node's type
      assertEquals("line 2: " + Text.quote(text) + " is not a NODE",
          declined("type: NODE\nvalue: " + Text.quote(text) + "\n"));
    }
    for (String text : List.of(
        "knows#0", // one row rather than two
        "knows#0->", // no second row
        "knows#->1", // no first row
        "knows#0-1", // the wrong arrow
        "#0->1", // no table
        "knows#0->-1")) { // a sign
      assertEquals("line 2: " + Text.quote(text) + " is not a EDGE",
          declined("type: EDGE\nvalue: " + Text.quote(text) + "\n"));
    }
  }

  /**
   * An integer is written back out and compared, so that a spelling a
   * parser would take and no other reader would is refused.
   */
  @Test
  @DisplayName("an integer is refused when it is spelt unusually")
  void anIntegerIsRefusedWhenItIsSpeltUnusually() {
    for (String text : List.of("+1", "01", "1_0", "0x10")) {
      assertEquals("line 2: " + Text.quote(text) + " is not a INT8",
          declined("type: INT8\nvalue: " + text + "\n"));
    }
    // Space around a bare payload never reaches here, because the reader
    // takes it off along with the space after the colon. This is asserted
    // rather than left implied, since it is the reason the list above has no
    // padded spelling in it.
    assertEquals(integer(1), value("type: INT8\nvalue:   1  \n"));
  }

  /**
   * A float is exact here: {@code 1} is an integer somebody meant to write
   * as {@code 1.0}, and {@code 1e400} is {@code inf} under another name.
   * The JVM's own parser takes four more spellings the other runners do
   * not.
   */
  @Test
  @DisplayName("a float is refused when it is spelt unusually")
  void aFloatIsRefusedWhenItIsSpeltUnusually() {
    for (String text : List.of("1", "-1", "1e400", "-1e400", "Infinity", "infinity", "nan",
        "0x1p-2", "1_0.0", "1.0f", "1.0d", "")) {
      assertEquals("line 2: " + Text.quote(text) + " is not a FLOAT64",
          declined("type: FLOAT64\nvalue: " + Text.quote(text) + "\n"));
    }
    for (String text : List.of("1.0", "-1.5", "1e10", "1E10", "1.5e-3", "NaN", "inf", "-inf")) {
      assertInstanceOf(Cell.Float.class, value("type: FLOAT64\nvalue: " + Text.quote(text) + "\n"),
          text + " is a float");
    }
  }

  /**
   * Space anywhere in a byte string is dropped, which is what the
   * standard's production allows and what lets a long literal be written in
   * groups. Half a byte is refused.
   */
  @Test
  @DisplayName("a byte string is hexits in either case and space is dropped")
  void aByteStringIsHexitsInEitherCaseAndSpaceIsDropped() {
    record Case(String text, byte[] want) {}
    for (Case c : List.of(
        new Case("00AB00", new byte[] {0, (byte) 0xAB, 0}),
        new Case("00ab00", new byte[] {0, (byte) 0xAB, 0}),
        new Case("00 AB 00", new byte[] {0, (byte) 0xAB, 0}),
        new Case("", new byte[0]),
        new Case("FF", new byte[] {(byte) 0xFF}))) {
      assertEquals(new Cell.Bytes(c.want()),
          value("type: BYTES\nvalue: " + Text.quote(c.text()) + "\n"),
          c.text() + " reads as those octets");
    }
    for (String text : List.of("0", "ABC", "GG", "0x41", "00-AB")) {
      assertEquals("line 2: " + Text.quote(text) + " is not a BYTES",
          declined("type: BYTES\nvalue: " + Text.quote(text) + "\n"));
    }
  }

  /**
   * Two values are the same value when their records are equal, which is
   * the whole reason {@link Cell} is records.
   *
   * <p>A float is the one that usually needs a comparison written by hand:
   * NaN is not equal to itself and a case asserting NaN has to pass, and 0.0
   * equals -0.0 and a case asserting -0.0 has to fail on 0.0. A record
   * compares a double with {@code Double.compare}, which is already both of
   * those answers.
   */
  @Test
  @DisplayName("two values are the same when equality says so, and a float is why that works")
  void twoValuesAreTheSameWhenEqualitySaysSo() {
    record Case(String what, Cell want, Cell got, boolean same) {}
    Cell nan = new Cell.Float(Double.NaN);
    for (Case c : List.of(
        new Case("NaN against itself", nan, new Cell.Float(Double.NaN), true),
        new Case("a negative zero against a positive one",
            new Cell.Float(-0.0), new Cell.Float(0.0), false),
        new Case("a positive zero against a negative one",
            new Cell.Float(0.0), new Cell.Float(-0.0), false),
        new Case("two ones", new Cell.Float(1.0), new Cell.Float(1.0), true),
        new Case("an integer against a float", integer(1), new Cell.Float(1.0), false),
        new Case("a boolean against an integer", new Cell.Bool(true), integer(1), false),
        new Case("nothing against nothing", Cell.NULL, new Cell.Null(), true),
        new Case("nothing against a value", Cell.NULL, integer(0), false),
        new Case("two lists", new Cell.List(List.of(integer(1), Cell.NULL)),
            new Cell.List(List.of(integer(1), Cell.NULL)), true),
        new Case("lists of different lengths", new Cell.List(List.of(integer(1))),
            new Cell.List(List.of(integer(1), Cell.NULL)), false),
        new Case("a list against a scalar", new Cell.List(List.of(integer(1))), integer(1), false),
        new Case("nested lists",
            new Cell.List(List.of(new Cell.List(List.of(new Cell.Float(1.0))))),
            new Cell.List(List.of(new Cell.List(List.of(new Cell.Float(1.0))))), true),
        new Case("two walks", new Cell.Path(List.of(new Cell.Node("p", 0))),
            new Cell.Path(List.of(new Cell.Node("p", 0))), true),
        new Case("a walk against a list", new Cell.Path(List.of(new Cell.Node("p", 0))),
            new Cell.List(List.of(new Cell.Node("p", 0))), false),
        new Case("two records", new Cell.Record(Map.of("a", integer(1))),
            new Cell.Record(Map.of("a", integer(1))), true),
        new Case("records of different sizes", new Cell.Record(Map.of("a", integer(1))),
            new Cell.Record(Map.of("a", integer(1), "b", Cell.NULL)), false),
        new Case("records with different names", new Cell.Record(Map.of("a", integer(1))),
            new Cell.Record(Map.of("b", integer(1))), false),
        new Case("a record against a scalar", new Cell.Record(Map.of("a", integer(1))),
            integer(1), false),
        new Case("two byte strings", new Cell.Bytes(new byte[] {1, 2}),
            new Cell.Bytes(new byte[] {1, 2}), true),
        new Case("byte strings that differ", new Cell.Bytes(new byte[] {1, 2}),
            new Cell.Bytes(new byte[] {1, 3}), false),
        new Case("a byte string against a string", new Cell.Bytes(new byte[] {65}),
            new Cell.Str("A"), false),
        new Case("two dates", date(1), date(1), true),
        new Case("dates that differ", date(1), date(2), false),
        new Case("a year month against a day time",
            new Cell.Time(new Value.Temporal(Value.Temporal.Kind.DURATION_YEAR_MONTH, 0, 0)),
            new Cell.Time(new Value.Temporal(Value.Temporal.Kind.DURATION_DAY_TIME, 0, 0)),
            false))) {
      assertEquals(c.same(), c.want().equals(c.got()), c.what());
      // And the hash, since a value that compares equal and hashes
      // differently is one a set would hold twice.
      if (c.same()) {
        assertEquals(c.want().hashCode(), c.got().hashCode(), c.what() + ", hashed");
      }
    }
  }

  private static Cell date(long days) {
    return new Cell.Time(new Value.Temporal(Value.Temporal.Kind.DATE, days, 0));
  }

  /**
   * What a failure report prints, in the encoding's own spelling so that a
   * line can be pasted back into a case.
   */
  @Test
  @DisplayName("show writes a value the way a case would spell it")
  void showWritesAValueTheWayACaseWouldSpellIt() {
    record Case(Cell value, String want) {}
    for (Case c : List.of(
        new Case(Cell.NULL, "NULL"),
        new Case(new Cell.Bool(true), "BOOL true"),
        new Case(new Cell.Bool(false), "BOOL false"),
        new Case(integer(-7), "INT64 \"-7\""),
        new Case(new Cell.Float(1.5), "FLOAT64 \"1.5\""),
        new Case(new Cell.Str("a string"), "STRING \"a string\""),
        new Case(new Cell.Str("with \"quotes\""), "STRING \"with \\\"quotes\\\"\""),
        new Case(new Cell.Bytes(new byte[] {0, (byte) 0xAB}), "BYTES \"00AB\""),
        new Case(new Cell.Bytes(new byte[0]), "BYTES \"\""),
        new Case(date(0), "DATE \"1970-01-01\""),
        new Case(time(Value.Temporal.Kind.LOCAL_TIME, 0, 0), "LOCALTIME \"00:00:00\""),
        new Case(time(Value.Temporal.Kind.LOCAL_TIME, 123456789, 0),
            "LOCALTIME \"00:00:00.123456789\""),
        new Case(time(Value.Temporal.Kind.ZONED_TIME, 0, 0), "ZONEDTIME \"00:00:00Z\""),
        new Case(time(Value.Temporal.Kind.LOCAL_DATETIME, 0, 0),
            "LOCALDATETIME \"1970-01-01T00:00:00\""),
        // The instant is UTC and the offset is what the case wrote, so the
        // clock printed beside it is the instant moved into that zone, which
        // is the wall clock the case reads back.
        new Case(time(Value.Temporal.Kind.ZONED_DATETIME, 0, 60),
            "ZONEDDATETIME \"1970-01-01T01:00:00+01:00\""),
        new Case(time(Value.Temporal.Kind.DURATION_YEAR_MONTH, 14, 0), "DURATION \"P1Y2M\""),
        new Case(time(Value.Temporal.Kind.DURATION_DAY_TIME, 0, 0), "DURATION \"PT0S\""),
        new Case(new Cell.List(List.of(integer(1), Cell.NULL)), "LIST [INT64 \"1\", NULL]"),
        new Case(new Cell.List(List.of()), "LIST []"),
        new Case(new Cell.Path(List.of(new Cell.Node("person", 0), new Cell.Edge("knows", 0, 1),
            new Cell.Node("person", 1))),
            "PATH [NODE \"person#0\", EDGE \"knows#0->1\", NODE \"person#1\"]"),
        new Case(new Cell.Node("person", 3), "NODE \"person#3\""),
        new Case(new Cell.Edge("knows", 3, 4), "EDGE \"knows#3->4\""),
        new Case(new Cell.Record(Map.of("b", integer(2), "a", Cell.NULL)),
            "RECORD {a: NULL, b: INT64 \"2\"}"),
        new Case(new Cell.Record(Map.of()), "RECORD {}"),
        // A value the corpus has no spelling for prints as itself, under a
        // name that is not a type, so a report carrying one cannot be
        // mistaken for a case that could be pasted back in.
        new Case(new Cell.Other(new Value.Graph()), "(Graph) Graph[]"))) {
      assertEquals(c.want(), Values.show(c.value()));
    }
    // A record's names are sorted, because a hash map's order is not stable
    // and a failure that reorders its own fields between runs is a failure
    // nobody can diff.
    Cell record = new Cell.Record(Map.of("z", Cell.NULL, "a", Cell.NULL, "m", Cell.NULL));
    for (int i = 0; i < 8; i++) {
      assertEquals("RECORD {a: NULL, m: NULL, z: NULL}", Values.show(record),
          "a record prints the same every time");
    }
  }

  private static Cell time(Value.Temporal.Kind kind, long count, int offset) {
    return new Cell.Time(new Value.Temporal(kind, count, offset));
  }

  /**
   * A float is printed the way Rust's {@code {:?}} writes one: the shortest
   * text that reads back as the same double, always with a point or an
   * exponent, switching to an exponent where Rust switches and writing the
   * exponent bare rather than with a sign and a padding zero.
   */
  @Test
  @DisplayName("a float prints the way the reference runner prints it")
  void aFloatPrintsTheWayTheReferenceRunnerPrintsIt() {
    record Case(double value, String want) {}
    for (Case c : List.of(
        new Case(0, "0.0"),
        new Case(-0.0, "-0.0"),
        new Case(1, "1.0"),
        new Case(-1, "-1.0"),
        new Case(1.5, "1.5"),
        new Case(0.1, "0.1"),
        new Case(1.0 / 3.0, "0.3333333333333333"),
        new Case(100, "100.0"),
        new Case(1e15, "1000000000000000.0"),
        // At ten to the sixteenth the digits go behind an exponent, which is
        // where Rust switches and not where the JVM's own printer does.
        new Case(1e16, "1e16"),
        new Case(1e17, "1e17"),
        new Case(1.5e17, "1.5e17"),
        new Case(0.001, "0.001"),
        new Case(0.0001, "0.0001"),
        // And below a ten thousandth, likewise.
        new Case(0.00001, "1e-5"),
        new Case(1.5e-5, "1.5e-5"),
        new Case(Double.MAX_VALUE, "1.7976931348623157e308"),
        new Case(Double.MIN_VALUE, "5e-324"),
        new Case(Double.NaN, "NaN"),
        new Case(Double.POSITIVE_INFINITY, "inf"),
        new Case(Double.NEGATIVE_INFINITY, "-inf"))) {
      assertEquals(c.want(), Values.showFloat(c.value()));
    }
  }

  /**
   * Everything a table holds is spelled the same on both sides and comes
   * through untouched. A graph value is not: the engine's node and edge
   * carry the id of their table where a case writes its name.
   */
  @Test
  @DisplayName("a cell turns the engine's graph values into the corpus spelling")
  void aCellTurnsTheEnginesGraphValuesIntoTheCorpusSpelling() {
    IntFunction<String> named = table -> table == 1 ? "person" : "knows";
    record Case(Value from, Cell want) {}
    for (Case c : List.of(
        new Case(new Value.Node(1, 4), new Cell.Node("person", 4)),
        new Case(new Value.Rel(2, 0, 1), new Cell.Edge("knows", 0, 1)),
        new Case(new Value.Path(List.of(new Value.Node(1, 0))),
            new Cell.Path(List.of(new Cell.Node("person", 0)))),
        new Case(new Value.List(List.of(new Value.Node(1, 2))),
            new Cell.List(List.of(new Cell.Node("person", 2)))),
        new Case(new Value.Record(List.of(new Value.Field("n", new Value.Node(1, 5)))),
            new Cell.Record(Map.of("n", new Cell.Node("person", 5)))),
        // Everything else comes through as it is.
        new Case(new Value.Int(1), integer(1)),
        new Case(Value.Null.instance(), Cell.NULL),
        new Case(new Value.Str("text"), new Cell.Str("text")))) {
      assertEquals(c.want(), Cell.of(c.from(), named));
    }
    // A list nested inside a list is walked all the way down, since a node
    // can be anywhere a value can.
    assertEquals(
        new Cell.List(List.of(new Cell.List(List.of(new Cell.Node("person", 9))))),
        Cell.of(new Value.List(List.of(new Value.List(List.of(new Value.Node(1, 9))))), named));
    // A graph is the one the corpus has no spelling for, and it is kept
    // rather than dropped so that a report can say what came back.
    Cell other = Cell.of(new Value.Graph(), named);
    assertInstanceOf(Cell.Other.class, other);
    assertNotNull(other);
    assertFalse(other.equals(Cell.NULL), "nothing a case can write is equal to it");
  }
}
