package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The mapping from a diagnostic record to the exception a caller catches. */
class DiagnosticTest {

  @ParameterizedTest
  @CsvSource({
    "08000, dev.zudb.ZuConnectionException",
    "08007, dev.zudb.ZuConnectionException",
    "22003, dev.zudb.ZuDataException",
    "22G03, dev.zudb.ZuDataException",
    "25000, dev.zudb.ZuTransactionException",
    "2D000, dev.zudb.ZuTransactionException",
    "40000, dev.zudb.ZuTransactionException",
    "42001, dev.zudb.ZuSyntaxException",
    "42N51, dev.zudb.ZuSyntaxException",
  })
  void theCodeClassPicksTheException(String code, String expected) throws Exception {
    Diagnostic d = diagnostic(Status.ERROR, code);
    assertInstanceOf(Class.forName(expected), d.toException());
  }

  @Test
  void aConditionClassIsCatchableInOneCatch() {
    // The point of mapping on the class rather than the whole code: all
    // forty-two conditions in class 22 arrive as one type.
    for (String code : new String[] {"22000", "22001", "22003", "22G0B", "22N63"}) {
      assertInstanceOf(ZuDataException.class, diagnostic(Status.ERROR, code).toException());
    }
  }

  @Test
  void aStatusWithNoCodePicksTheException() {
    assertInstanceOf(
        ZuProgrammingException.class, diagnostic(Status.MISUSE, null).toException());
    assertInstanceOf(
        ZuConcurrentException.class, diagnostic(Status.MISUSE_CONCURRENT, null).toException());
    assertInstanceOf(ZuClosedException.class, diagnostic(Status.MISUSE_CLOSED, null).toException());
    assertInstanceOf(
        ZuInterruptedException.class, diagnostic(Status.INTERRUPTED, null).toException());
    assertInstanceOf(ZuTransactionException.class, diagnostic(Status.CONFLICT, null).toException());
    assertInstanceOf(ZuConnectionException.class, diagnostic(Status.IO, null).toException());
    // A file that is not a database is a path mistake, not a bug in the
    // engine, so it lands beside the file that is not there rather than in
    // the class that asks the caller to report it.
    assertInstanceOf(ZuConnectionException.class, diagnostic(Status.CORRUPT, null).toException());
    assertInstanceOf(ZuInternalException.class, diagnostic(Status.UNKNOWN, null).toException());
  }

  @Test
  void theConcurrentAndClosedMistakesAreProgrammingMistakes() {
    // A pool that catches ZuProgrammingException catches both of these,
    // which is the point of them being subclasses of it.
    assertTrue(
        ZuProgrammingException.class.isAssignableFrom(ZuConcurrentException.class));
    assertTrue(ZuProgrammingException.class.isAssignableFrom(ZuClosedException.class));
  }

  @Test
  void anExceptionCarriesTheWholeRecord() {
    Diagnostic d =
        new Diagnostic(
            Status.ERROR,
            "no such table: persn",
            "42N51",
            "syntax error or access rule violation",
            Severity.EXCEPTION,
            2,
            9,
            17,
            "MATCH (p:persn)",
            "https://zudb.dev/errors/42N51",
            false);
    ZuException e = d.toException();
    assertEquals("no such table: persn", e.getMessage());
    assertEquals(Status.ERROR, e.status());
    assertEquals("42N51", e.code().orElseThrow());
    assertEquals(Severity.EXCEPTION, e.severity());
    assertEquals(new ZuException.Position(2, 9, 17), e.position().orElseThrow());
    assertEquals("MATCH (p:persn)", e.excerpt().orElseThrow());
    assertFalse(e.retryable());
  }

  @Test
  void aCaretUnderlinesTheColumnTheExcerptCounts() {
    Diagnostic d =
        new Diagnostic(
            Status.ERROR, "bad", "42001", null, Severity.EXCEPTION, 1, 8, 7, "RETURN ?", null,
            false);
    String caret = d.toException().caret().orElseThrow();
    assertEquals("RETURN ?", caret.lines().findFirst().orElseThrow());
    assertTrue(caret.endsWith("^"));
    // Seven characters of lead-in, then the caret under the eighth.
    assertEquals(7, caret.lines().skip(1).findFirst().orElseThrow().indexOf('^'));
  }

  @Test
  void aRecordWithNoPositionHasNoCaret() {
    assertTrue(diagnostic(Status.ERROR, "22012").toException().caret().isEmpty());
  }

  @Test
  void theRawFactoryMapsBothNumbers() {
    Diagnostic d =
        Diagnostic.of(3, "boom", "22012", "data exception", 4, 1, 1, 0, null, null, true);
    assertEquals(Status.ERROR, d.status());
    assertEquals(Severity.EXCEPTION, d.severity());
    assertTrue(d.retryable());
  }

  @Test
  void aStatusThisClientDoesNotKnowIsUnknownRatherThanAThrow() {
    // A library newer than this client is a thing that happens, and the
    // right answer is a failure that says so, not a crash in the mapping.
    assertEquals(Status.UNKNOWN, Status.of(9999));
  }

  private static Diagnostic diagnostic(Status status, String code) {
    return new Diagnostic(
        status, "boom", code, null, Severity.EXCEPTION, -1, -1, -1, null, null, false);
  }
}
