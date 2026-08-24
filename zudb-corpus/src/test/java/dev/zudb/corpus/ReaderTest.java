package dev.zudb.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reader, tested against the subset it claims to read and against the
 * constructs it claims to refuse.
 *
 * <p>Both halves matter and the second one is the reason this file is
 * long. A reader that accepts everything the corpus writes is half a
 * reader: the other half is that a case using a block scalar, an anchor or
 * a flow sequence is refused with a line number rather than read as
 * something the author did not write. Five implementations of this subset
 * exist and a construct one of them quietly accepts is a case that passes
 * in one repository and fails in four.
 *
 * <p>The refusal messages are checked in full rather than by substring,
 * because they are diffed against the reference runner's and a wording
 * that drifted would be a difference in the report that is not a
 * difference in the answer.
 */
class ReaderTest {

  /** The message a document was refused with, and a failure when it was read. */
  private static String refused(String text) {
    return assertThrows(CorpusException.class, () -> Yaml.parse(text)).getMessage();
  }

  @Test
  void aMappingKeepsItsKeysInTheOrderTheyWereWritten() {
    Node doc = Yaml.parse("suite: string\ndoc: what a string does\nschema: 4\n");
    List<Node.Pair> pairs = doc.map();
    assertNotNull(pairs, "the document should be a mapping");
    assertEquals(List.of("suite", "doc", "schema"), pairs.stream().map(Node.Pair::key).toList());
    assertEquals("what a string does", doc.get("doc").text());
    assertNull(doc.get("load"), "get answered a key that is not in the mapping");
  }

  @Test
  void aSequenceOfMappingsIsOneNodePerItem() {
    Node doc = Yaml.parse(String.join("\n",
        "cases:",
        "  - name: one",
        "    query: RETURN 1",
        "  - name: two",
        "    query: RETURN 2",
        ""));
    List<Node> items = doc.get("cases").seq();
    assertNotNull(items, "`cases:` should be a sequence");
    assertEquals(2, items.size());
    assertEquals("one", items.get(0).get("name").text());
    assertEquals("two", items.get(1).get("name").text());
    // The line a refusal would cite is the line the item opened on and not
    // the line the sequence did, which is the whole reason a node carries
    // one.
    assertEquals(4, items.get(1).line());
  }

  @Test
  void aDashAndItsFirstKeyMayShareALine() {
    // A `- ` and the key it opens are one line in the file and two lines
    // by the time the parser sees them, and the split is what lets an item
    // written on one line and an item written under its dash be the same
    // shape. Both spellings are in the corpus.
    Node together = Yaml.parse("cases:\n  - name: one\n    query: RETURN 1\n");
    Node apart = Yaml.parse("cases:\n  -\n    name: one\n    query: RETURN 1\n");
    for (Node doc : List.of(together, apart)) {
      List<Node> items = doc.get("cases").seq();
      assertNotNull(items);
      assertEquals(1, items.size());
      assertEquals("one", items.get(0).get("name").text());
    }
  }

  @Test
  void aScalarRemembersWhetherItWasQuoted() {
    Node doc = Yaml.parse("bare: 42\nsingle: '42'\ndouble: \"42\"\n");
    assertEquals("42", doc.get("bare").text());
    assertEquals("42", doc.get("single").text());
    assertEquals("42", doc.get("double").text());
    assertEquals(false, doc.get("bare").quoted());
    assertTrue(doc.get("single").quoted());
    assertTrue(doc.get("double").quoted());
  }

  @Test
  void aSingleQuotedRunEscapesOnlyByDoublingTheQuote() {
    Node doc = Yaml.parse("query: 'RETURN ''it''''s'' AS s'\n");
    assertTrue(doc.get("query").quoted());
    assertEquals("RETURN 'it''s' AS s", doc.get("query").text());
    // A backslash inside a single quoted run is a backslash, which is what
    // lets a case write a regular expression without doubling every one of
    // them.
    Node back = Yaml.parse("query: 'a\\nb'\n");
    assertEquals("a\\nb", back.get("query").text());
  }

  @Test
  void aDoubleQuotedRunTakesTheEscapesTheCorpusUses() {
    Node doc = Yaml.parse("text: \"a\\nb\\tc\\\\d\\\"e\\r\\0f\\bg\"\n");
    assertEquals("a\nb\tc\\d\"e\r\0f\bg", doc.get("text").text());
  }

  @Test
  void aCommentGoesAndAHashInsideAValueStays() {
    // A comment is dropped, and the three rules that keep the dropping
    // from eating content are each worth a line: a # inside a word is part
    // of the word, a quote inside a word is part of the word, and a quote
    // that opens nothing that closes was not a run.
    record Case(String text, String key, String want) {}
    List<Case> cases = List.of(
        new Case("a: 1 # why\n", "a", "1"),
        new Case("# whole line\nb: 2\n", "b", "2"),
        new Case("c: person#1\n", "c", "person#1"),
        new Case("d: 'a # b'\n", "d", "a # b"),
        new Case("e: it's a plain scalar # and a comment\n", "e", "it's a plain scalar"),
        new Case("f: cast('  42  ' AS INT64)\n", "f", "cast('  42  ' AS INT64)"),
        new Case("g: RETURN 'a' AS a # a comment after a run that closed\n", "g",
            "RETURN 'a' AS a"));
    for (Case c : cases) {
      assertEquals(c.want(), Yaml.parse(c.text()).get(c.key()).text(), Text.quote(c.text()));
    }
  }

  @Test
  void aKeyWithNothingUnderItIsAnEmptyNode() {
    // A key with nothing under it is a node rather than a refusal, because
    // a case that expects no rows writes "rows:" and stops. Every accessor
    // says no to it, so a "name:" somebody left blank is still caught.
    Node doc = Yaml.parse("rows:\nname: after\n");
    Node empty = doc.get("rows");
    assertEquals(Node.Kind.EMPTY, empty.kind());
    assertEquals("nothing", empty.what());
    assertNull(empty.text(), "text answered an empty node");
    assertNull(empty.seq(), "seq answered an empty node");
    assertNull(empty.map(), "map answered an empty node");
    assertEquals(List.of(), empty.seqOrEmpty(), "empty is the answer seqOrEmpty is for");
    // The key after it is still read, so an empty value ends at its own
    // line rather than swallowing what follows.
    assertEquals("after", doc.get("name").text());
  }

  @Test
  void unknownNamesTheKeysThatAreNotExpected() {
    Node doc = Yaml.parse("name: one\nquery: RETURN 1\nqeury: RETURN 2\nrows:\n");
    assertEquals(List.of("qeury"), doc.unknown("name", "query", "rows"));
    assertEquals(List.of(), doc.unknown("name", "query", "qeury", "rows"));
    // A scalar has no keys and is not a mapping, so it has no unknown ones
    // either rather than being a refusal at this level.
    assertEquals(List.of(), Yaml.parse("just a scalar\n").unknown("name"));
  }

  @Test
  void whatSaysWhichShapeANodeIs() {
    assertEquals("a scalar", Yaml.parse("a plain scalar\n").what());
    assertEquals("a sequence", Yaml.parse("- one\n- two\n").what());
    assertEquals("a mapping", Yaml.parse("key: value\n").what());
  }

  @Test
  void theConstructsThisReaderDoesNotRead() {
    // Every one of these is real YAML that a general reader would take,
    // and every one of them would mean a case says one thing to a reviewer
    // and another to the runner.
    record Case(String what, String text, String want) {}
    List<Case> cases = List.of(
        new Case("a tab",
            "cases:\n\t- name: one\n",
            "line 2: a tab at column 1, and indentation here is spaces"),
        new Case("a document marker",
            "---\nschema: 4\n",
            "line 1: \"---\" opens or closes a document, and a file here holds one"),
        new Case("a document terminator",
            "schema: 4\n...\n",
            "line 2: \"...\" opens or closes a document, and a file here holds one"),
        new Case("an odd indent",
            "cases:\n   - name: one\n",
            "line 2: indented 3, and indentation here goes two spaces at a time"),
        new Case("a dash with two spaces after it",
            "cases:\n  -  name: one\n",
            "line 2: a `- ` takes exactly one space, so that what follows it lines up with the "
                + "lines under it"),
        new Case("a sequence opening into a sequence",
            "cases:\n  - - one\n",
            "line 2: a sequence opening straight into another one, which nothing here needs"),
        new Case("a dash with nothing after it",
            "cases:\n  -\n",
            "line 2: a `-` with nothing after it"),
        new Case("a flow sequence",
            "columns: [a, b]\n",
            "line 1: a plain scalar opening with '[', which is a construct this reader does not "
                + "read"),
        new Case("a flow mapping",
            "value: {type: INT64}\n",
            "line 1: a plain scalar opening with '{', which is a construct this reader does not "
                + "read"),
        new Case("an anchor",
            "row: &base one\n",
            "line 1: a plain scalar opening with '&', which is a construct this reader does not "
                + "read"),
        new Case("an alias",
            "row: *base\n",
            "line 1: a plain scalar opening with '*', which is a construct this reader does not "
                + "read"),
        new Case("a tag",
            "count: !!int 4\n",
            "line 1: a plain scalar opening with '!', which is a construct this reader does not "
                + "read"),
        new Case("a literal block scalar",
            "doc: |\n  one\n",
            "line 1: a plain scalar opening with '|', which is a construct this reader does not "
                + "read"),
        new Case("a folded block scalar",
            "doc: >\n  one\n",
            "line 1: a plain scalar opening with '>', which is a construct this reader does not "
                + "read"),
        new Case("a directive",
            "query: %YAML 1.2\n",
            "line 1: a plain scalar opening with '%', which is a construct this reader does not "
                + "read"),
        new Case("a run that does not close",
            "query: \"RETURN 1\n",
            "line 1: a \" that opens and does not close on its line"),
        new Case("two runs on one line",
            "query: \"a\" and \"b\"\n",
            "line 1: \" and \\\"b\\\"\" after the scalar ends"),
        // The backslash escapes the quote that would have closed the run,
        // so this is reported as a run left open rather than as a scalar
        // ending in a backslash. Both messages are in the reader and this
        // is the one that is reachable, since a backslash before the
        // closing quote always takes the quote with it.
        new Case("a double quoted run whose last character escapes its quote",
            "query: \"a\\\"\n",
            "line 1: a \" that opens and does not close on its line"),
        new Case("an escape this reader has no rule for",
            "query: \"a\\x41b\"\n",
            "line 1: \\x is not an escape"),
        new Case("a key set twice",
            "name: one\nname: two\n",
            "line 2: name is set twice in one mapping"),
        new Case("an indent under a key that is not two",
            "load:\n    nodes: person\n",
            "line 2: indented 4, where what is under `load:` on line 1 is indented 2"),
        new Case("an indent under a dash that is not two",
            "cases:\n  -\n      name: one\n",
            "line 3: indented 6, where an item of the sequence on line 2 is indented 4"),
        new Case("a first line that is indented",
            "  schema: 4\n",
            "line 1: the first line is indented"),
        new Case("a file with nothing in it",
            "# only a comment\n\n",
            "the file has nothing in it"),
        new Case("a line belonging to nothing above it",
            "just a scalar\nand another\n",
            "line 2: this belongs to nothing above it"));
    for (Case c : cases) {
      assertEquals(c.want(), refused(c.text()), c.what());
    }
  }

  @Test
  void quoteWritesAStringTheWayTheOtherRunnersDo() {
    // Rust's {:?} and not Java's own escaping, because a refusal written
    // in five languages and diffed across them cannot have one of them
    // escaping a character the others print.
    assertEquals("\"plain\"", Text.quote("plain"));
    assertEquals("\"a \\\"quoted\\\" word\"", Text.quote("a \"quoted\" word"));
    assertEquals("\"a\\\\backslash\"", Text.quote("a\\backslash"));
    assertEquals("\"a\\nb\"", Text.quote("a\nb"));
    assertEquals("\"a\\rb\"", Text.quote("a\rb"));
    assertEquals("\"a\\tb\"", Text.quote("a\tb"));
    // The one a general escaper would touch and Rust would not.
    assertEquals("\"héllo → 世界\"", Text.quote("héllo → 世界"));
    assertEquals("\"\"", Text.quote(""));
  }
}
