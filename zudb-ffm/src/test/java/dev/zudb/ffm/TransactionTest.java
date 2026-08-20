package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Beginning, committing and rolling back, and the block that does all three. */
class TransactionTest {

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void aConnectionKnowsWhetherItIsInOne() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      assertFalse(conn.inTransaction());
      conn.begin();
      assertTrue(conn.inTransaction());
      conn.commit();
      assertFalse(conn.inTransaction());
    }
  }

  @Test
  void aRollbackEndsItToo() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      conn.begin();
      conn.rollback();
      assertFalse(conn.inTransaction());
    }
  }

  @Test
  void aReadOnlyTransactionIsStillATransaction() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      conn.beginReadOnly();
      assertTrue(conn.inTransaction());
      try (Result r = conn.query("RETURN 1 AS one")) {
        assertEquals(1, r.rows());
      }
      conn.commit();
    }
  }

  @Test
  void aStatementRunsInsideAnOpenTransaction() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      conn.begin();
      try (Result r = conn.query("UNWIND [1, 2] AS v RETURN v")) {
        assertEquals(2, r.rows());
      }
      conn.commit();
    }
  }

  @Test
  void theBlockCommitsWhenTheBodyReturns() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      long answer = conn.transaction(() -> 7L);
      assertEquals(7, answer);
      assertFalse(conn.inTransaction());
    }
  }

  @Test
  void theBlockRollsBackWhenTheBodyThrowsAndTheThrowIsTheOneYouGet() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () ->
                  conn.transaction(
                      () -> {
                        throw new IllegalStateException("no");
                      }));
      assertEquals("no", e.getMessage());
      assertFalse(conn.inTransaction());
    }
  }

  @Test
  void theBlockWithNoAnswerRunsTheSameWay() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      StringBuilder ran = new StringBuilder();
      conn.transaction(() -> ran.append("yes"));
      assertEquals("yes", ran.toString());
      assertFalse(conn.inTransaction());
    }
  }

  @Test
  void twoConnectionsOnOneDatabaseHaveTransactionsOfTheirOwn() {
    try (Database db = Database.memory();
        Connection first = db.connect();
        Connection second = db.connect()) {
      first.begin();
      assertTrue(first.inTransaction());
      assertFalse(second.inTransaction());
      first.rollback();
    }
  }
}
