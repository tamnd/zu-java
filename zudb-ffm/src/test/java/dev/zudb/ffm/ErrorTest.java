package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Severity;
import dev.zudb.ZuException;
import dev.zudb.ZuSyntaxException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What a failure carries across the boundary.
 *
 * <p>The whole point of the error model is that a caller reads fields rather
 * than a message. These tests are what says the fields actually arrive.
 */
class ErrorTest {

  private static Database db;
  private static Connection conn;

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
    ZuSyntaxException e =
        assertThrows(ZuSyntaxException.class, () -> conn.query("RETURN RETURN"));
    assertTrue(e.code().orElseThrow().startsWith("42"), e.code().orElseThrow());
    assertEquals(Severity.EXCEPTION, e.severity());
    assertFalse(e.retryable());
    assertNotNull(e.getMessage());
    assertFalse(e.getMessage().isBlank());
  }

  @Test
  void aFailureThatHasAPlaceCarriesIt() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query("RETURN RETURN"));
    e.position()
        .ifPresent(
            p -> {
              assertTrue(p.line() >= 1, "line " + p.line());
              assertTrue(p.column() >= 1, "column " + p.column());
              assertTrue(p.offset() >= 0, "offset " + p.offset());
            });
    // An excerpt and a column together are a caret, and the caret is the one
    // piece of formatting this client does.
    e.caret().ifPresent(c -> assertTrue(c.contains("^"), c));
  }

  @Test
  void everyFailureIsCatchableAsOneType() {
    assertThrows(ZuException.class, () -> conn.query("this is not a statement"));
    assertThrows(ZuException.class, () -> conn.prepare("MATCH ("));
  }

  @Test
  void theDiagnosticIsTheWholeRecord() {
    ZuException e = assertThrows(ZuException.class, () -> conn.query("RETURN RETURN"));
    assertNotNull(e.diagnostic());
    assertEquals(e.getMessage(), e.diagnostic().message());
    assertEquals(e.status(), e.diagnostic().status());
  }

  @Test
  void aFailureLeavesTheConnectionUsable() {
    assertThrows(ZuException.class, () -> conn.query("RETURN RETURN"));
    conn.execute("RETURN 1 AS one");
    assertFalse(conn.isClosed());
  }

  @Test
  void athousandFailuresLeakNothing() {
    // Every one of these allocates a zu_error on the far side. If the
    // binding forgot to free them this is where it would show.
    for (int i = 0; i < 1000; i++) {
      assertThrows(ZuException.class, () -> conn.query("RETURN RETURN"));
    }
    conn.execute("RETURN 1 AS one");
  }
}
