package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Config;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Diagnostic;
import dev.zudb.Severity;
import dev.zudb.Status;
import dev.zudb.ZuClosedException;
import dev.zudb.ZuConnectionException;
import dev.zudb.ZuException;
import dev.zudb.ZuProgrammingException;
import dev.zudb.ZuSyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a failure carries across the boundary.
 *
 * <p>The whole point of the error model is that a caller reads fields rather
 * than a message. These tests are what says the fields actually arrive.
 *
 * <p>They assert rather than inspect. A test written as {@code
 * e.position().ifPresent(p -> assertTrue(...))} passes when the position is
 * missing, which is the one outcome worth failing for, and a suite full of
 * those is a page of promises with a green tick on it. Where a field is
 * genuinely optional the case that has it and the case that does not are two
 * tests, and each one says which it is.
 */
public class ErrorTest {

  /** Where a condition is written up. The engine puts the code on the end. */
  private static final String DOCS = "https://zu.dev/docs/errors/";

  /** A statement that fails to parse, with the mistake in the middle of it. */
  private static final String MISSPELLED = "MATCH (p:person) RETRUN p";

  private static Database db;
  private static Connection conn;

  @TempDir static Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
    db = Database.memory();
    conn = db.connect();
  }

  @AfterAll
  static void done() {
    if (conn != null) {
      conn.close();
    }
    if (db != null) {
      db.close();
    }
  }

  @Test
  void textThatWillNotParseIsASyntaxError() {
    ZuSyntaxException e = assertThrows(ZuSyntaxException.class, () -> conn.query(MISSPELLED));
    assertTrue(e.code().orElseThrow().startsWith("42"), e.code().orElseThrow());
    assertFalse(e.condition().orElseThrow().isBlank());
    assertEquals(Severity.EXCEPTION, e.severity());
    assertFalse(e.retryable());
    assertNotNull(e.getMessage());
    assertFalse(e.getMessage().isBlank());
  }

  @Test
  void aFailureInTheTextCarriesThePlaceItIsIn() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    ZuException.Position p = e.position().orElseThrow();
    assertEquals(1, p.line());
    assertTrue(p.column() > 1, "column " + p.column());
    assertTrue(p.offset() > 0, "offset " + p.offset());
    // The offset is where the token starts, said in bytes, and a caller who
    // still has the text slices at it rather than counting columns.
    assertTrue(MISSPELLED.substring(p.offset()).startsWith("RETRUN"), "offset " + p.offset());
  }

  @Test
  void theDocUrlIsThePageForTheCode() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    // Not a search box and not five characters to go and look up. The code
    // goes on the end exactly as it is, because a condition class is written
    // with a letter in it and a URL that lower cased it would be a 404.
    assertEquals(DOCS + e.code().orElseThrow(), e.docUrl().orElseThrow());
  }

  @Test
  void anExcerptAndACaretPointAtTheToken() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    assertEquals(MISSPELLED, e.excerpt().orElseThrow());
    String[] lines = e.caret().orElseThrow().split("\\R");
    assertEquals(2, lines.length);
    assertEquals(MISSPELLED, lines[0]);
    assertEquals(e.position().orElseThrow().column() - 1, lines[1].indexOf('^'));
  }

  @Test
  void aNameNothingBoundIsAReferenceError() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query("RETURN nobody AS x"));
    assertEquals("42002", e.code().orElseThrow());
    assertEquals(DOCS + "42002", e.docUrl().orElseThrow());
  }

  @Test
  void aMistakeTheProgramMadeIsAProgrammingError() {
    Database other = Database.memory();
    Connection closed = other.connect();
    closed.close();
    ZuClosedException e =
        assertThrows(ZuClosedException.class, () -> closed.query("RETURN 1 AS one"));
    // A call that never reached the engine has no condition to name and says
    // so, rather than borrowing one that would send a reader to the wrong
    // page. The class is the diagnosis here, and the status behind it.
    assertEquals(Status.MISUSE_CLOSED, e.status());
    assertTrue(e.position().isEmpty());
    assertTrue(e.docUrl().isEmpty());
    assertFalse(e.getMessage().isBlank());
    other.close();
  }

  @Test
  void aDatabaseThatIsNotThereIsAConnectionError() {
    Path missing = dir.resolve("not-here.zu1");
    ZuConnectionException e =
        assertThrows(
            ZuConnectionException.class,
            () -> Database.open(missing, Config.defaults().withReadOnly(true)));
    assertFalse(e.getMessage().isBlank());
    // The path is the one thing the caller cannot work out from the class,
    // so a message that leaves it out is a message that starts a bisect.
    assertTrue(e.getMessage().contains("not-here"), e.getMessage());
  }

  @Test
  void everyFailureIsCatchableAsOneType() {
    assertThrows(ZuException.class, () -> conn.query("this is not a statement"));
    assertThrows(ZuException.class, () -> conn.prepare("MATCH ("));
  }

  @Test
  void aConditionClassIsCatchableWithoutListingItsConditions() {
    // The two characters that open a code are the condition class, and there
    // is one subclass per class so that a condition added to class 42 later
    // is caught by a catch somebody wrote today.
    assertTrue(ZuException.class.isAssignableFrom(ZuSyntaxException.class));
    assertTrue(ZuException.class.isAssignableFrom(ZuConnectionException.class));
    assertTrue(ZuException.class.isAssignableFrom(ZuProgrammingException.class));
    assertTrue(ZuProgrammingException.class.isAssignableFrom(ZuClosedException.class));
    // Unchecked, which is the decision the class comment argues for.
    assertTrue(RuntimeException.class.isAssignableFrom(ZuException.class));
  }

  @Test
  void theDiagnosticIsTheWholeRecord() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    assertNotNull(e.diagnostic());
    assertEquals(e.getMessage(), e.diagnostic().message());
    assertEquals(e.status(), e.diagnostic().status());
    assertEquals(e.code().orElseThrow(), e.diagnostic().code());
    assertEquals(e.docUrl().orElseThrow(), e.diagnostic().docUrl());
  }

  @Test
  void aConditionThatNeverHappenedLeavesTheFieldsEmpty() {
    // The record a call that never reached the engine is built from. Nothing
    // is guessed into it, because a position of zero would be a place in the
    // text and there is no text.
    ZuException e = Diagnostic.misuse(Status.MISUSE, "nothing in particular").toException();
    assertEquals("nothing in particular", e.getMessage());
    assertTrue(e.code().isEmpty());
    assertTrue(e.condition().isEmpty());
    assertTrue(e.position().isEmpty());
    assertTrue(e.excerpt().isEmpty());
    assertTrue(e.caret().isEmpty());
    assertTrue(e.docUrl().isEmpty());
    assertFalse(e.retryable());
  }

  @Test
  void aFailureLeavesTheConnectionUsable() {
    assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    conn.execute("RETURN 1 AS one");
    assertFalse(conn.isClosed());
  }

  @Test
  void aThousandFailuresLeakNothing() {
    // Every one of these allocates a zu_error on the far side. If the
    // binding forgot to free them this is where it would show.
    for (int i = 0; i < 1000; i++) {
      assertThrows(ZuException.class, () -> conn.query(MISSPELLED));
    }
    conn.execute("RETURN 1 AS one");
  }
}
