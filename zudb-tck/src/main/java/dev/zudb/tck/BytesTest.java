package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Type;
import dev.zudb.Value;
import dev.zudb.ZuProgrammingException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Byte strings, which are octets and not text.
 *
 * <p>The point of a separate type is that nothing here decodes: a client that
 * read these as a string would turn octets that are not UTF-8 into
 * replacement characters and never say it had.
 */
public class BytesTest {

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
  void aByteStringLiteralComesBackAsBytes() {
    try (Result r = conn.query("RETURN X'00AB00' AS b")) {
      assertEquals(Type.BYTES, r.row(0).type("b"));
      Value.Bytes bytes = assertInstanceOf(Value.Bytes.class, r.row(0).get("b"));
      assertArrayEquals(new byte[] {0x00, (byte) 0xAB, 0x00}, bytes.value());
    }
  }

  @Test
  void theTypedAccessorHandsOverTheOctets() {
    try (Result r = conn.query("RETURN X'DEADBEEF' AS b")) {
      assertArrayEquals(
          new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, r.row(0).getBytes("b"));
      assertArrayEquals(r.row(0).getBytes("b"), r.row(0).getBytes(0));
    }
  }

  @Test
  void noOctetsAtAllIsAnEmptyArrayAndNotANull() {
    try (Result r = conn.query("RETURN X'' AS b")) {
      assertEquals(Type.BYTES, r.row(0).type("b"));
      assertArrayEquals(new byte[0], r.row(0).getBytes("b"));
    }
  }

  @Test
  void aNullCellIsNullRatherThanAThrow() {
    try (Result r = conn.query("RETURN null AS b")) {
      assertNull(r.row(0).getBytes("b"));
    }
  }

  @Test
  void aStringIsRefusedRatherThanEncoded() {
    try (Result r = conn.query("RETURN 'ada' AS s")) {
      // The two are different types. A client that quietly turned one into
      // the other would be answering a question nobody asked.
      assertThrows(ZuProgrammingException.class, () -> r.row(0).getBytes("s"));
    }
  }

  @Test
  void byteStringsWithTheSameOctetsAreOneValue() {
    // A record over an array compares by identity unless it is written out,
    // and two byte strings holding the same octets are one value.
    assertEquals(new Value.Bytes(new byte[] {1, 2}), new Value.Bytes(new byte[] {1, 2}));
    assertEquals(
        new Value.Bytes(new byte[] {1, 2}).hashCode(),
        new Value.Bytes(new byte[] {1, 2}).hashCode());
    assertNotEquals(new Value.Bytes(new byte[] {1, 2}), new Value.Bytes(new byte[] {1, 3}));
  }

  @Test
  void aByteStringSpellsItselfInHex() {
    assertEquals("X'00AB00'", new Value.Bytes(new byte[] {0, (byte) 0xAB, 0}).toString());
    assertEquals("X''", new Value.Bytes(new byte[0]).toString());
  }

  @Test
  void theArrayIsTheCallersOwnCopy() {
    try (Result r = conn.query("RETURN X'0102' AS b")) {
      byte[] first = r.row(0).getBytes("b");
      first[0] = 0x7F;
      assertArrayEquals(new byte[] {0x01, 0x02}, r.row(0).getBytes("b"));
    }
  }

  @Test
  void aByteStringInATreeOutlivesTheResult() {
    Value value;
    try (Result r = conn.query("RETURN [X'01', X'02'] AS v")) {
      value = r.row(0).get("v");
    }
    Value.List list = assertInstanceOf(Value.List.class, value);
    assertEquals(new Value.Bytes(new byte[] {1}), list.items().get(0));
    assertEquals(new Value.Bytes(new byte[] {2}), list.items().get(1));
  }
}
