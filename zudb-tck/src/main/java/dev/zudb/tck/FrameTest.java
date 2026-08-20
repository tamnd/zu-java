package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Value;
import dev.zudb.ZuException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Querying memory the test already holds, without any of it getting into a
 * database.
 */
public class FrameTest {

  @TempDir Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void aColumnOfLongsIsQueriedWhereItLies() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 3)) {
      frame.column("id", longs(1, 2, 3));
      conn.register(frame);
      try (Result r = conn.query("MATCH (p:Person) RETURN p.id AS id ORDER BY id")) {
        assertEquals(List.of(1L, 2L, 3L), r.stream().map(row -> row.getLong(0)).toList());
      }
    }
  }

  @Test
  void everyWidthAndSignComesBackAsTheNumberItIs() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Wide", 2)) {
      frame.column("big", longs(1, -2));
      frame.column("mid", ints(3, -4));
      frame.column("small", shorts((short) 5, (short) -6));
      frame.integers("tiny", bytes((byte) 7, (byte) 8), 2, 8, false, 1, null);
      frame.integers("unsigned", ints(9, 10), 2, 32, false, 1, null);
      conn.register(frame);
      try (Result r =
          conn.query("MATCH (w:Wide) RETURN w.big, w.mid, w.small, w.tiny, w.unsigned")) {
        assertEquals(1L, r.row(0).getLong(0));
        assertEquals(3L, r.row(0).getLong(1));
        assertEquals(5L, r.row(0).getLong(2));
        assertEquals(7L, r.row(0).getLong(3));
        assertEquals(9L, r.row(0).getLong(4));
        assertEquals(-2L, r.row(1).getLong(0));
        assertEquals(-4L, r.row(1).getLong(1));
        assertEquals(-6L, r.row(1).getLong(2));
        assertEquals(8L, r.row(1).getLong(3));
        assertEquals(10L, r.row(1).getLong(4));
      }
    }
  }

  @Test
  void bothWidthsOfFloatComeBack() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Reading", 2)) {
      frame.column("wide", doubles(1.5, 2.5));
      frame.column("narrow", floats(0.5f, 0.25f));
      conn.register(frame);
      try (Result r = conn.query("MATCH (x:Reading) RETURN x.wide, x.narrow")) {
        assertEquals(1.5, r.row(0).getDouble(0));
        assertEquals(0.5, r.row(0).getDouble(1));
        assertEquals(2.5, r.row(1).getDouble(0));
        assertEquals(0.25, r.row(1).getDouble(1));
      }
    }
  }

  @Test
  void aBitmapIsAColumnOfBooleans() {
    // 0b00001101: true, false, true, true.
    ByteBuffer bitmap = ByteBuffer.allocateDirect(1);
    bitmap.put((byte) 0b1101).flip();
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Flag", 4)) {
      frame.booleans("on", bitmap, 4);
      conn.register(frame);
      try (Result r = conn.query("MATCH (f:Flag) RETURN f.on")) {
        assertEquals(
            List.of(true, false, true, true),
            r.stream().map(row -> row.getBoolean(0)).toList());
      }
    }
  }

  @Test
  void charactersEndToEndAreAColumnOfStrings() {
    List<String> words = List.of("ada", "", "grace", "éàü", "a".repeat(300));
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Word", words.size())) {
      frame.strings("text", offsets32(words), characters(words));
      conn.register(frame);
      try (Result r = conn.query("MATCH (w:Word) RETURN w.text")) {
        assertEquals(words, r.stream().map(row -> row.getString(0)).toList());
      }
    }
  }

  @Test
  void sixtyFourBitOffsetsAreTheSameColumn() {
    List<String> words = List.of("ada", "grace", "alan");
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Word", words.size())) {
      frame.strings("text", offsets64(words), characters(words));
      conn.register(frame);
      try (Result r = conn.query("MATCH (w:Word) RETURN w.text")) {
        assertEquals(words, r.stream().map(row -> row.getString(0)).toList());
      }
    }
  }

  @Test
  void aViewColumnIsReadWithoutItsCharactersMoving() {
    // Short strings live in the view itself, long ones point into the data
    // buffer, and both have to come back the same.
    List<String> words = List.of("ada", "a string too long to live in sixteen bytes");
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Word", words.size())) {
      ByteBuffer data = characters(words);
      frame.views("text", views(words), List.of(data));
      conn.register(frame);
      try (Result r = conn.query("MATCH (w:Word) RETURN w.text")) {
        assertEquals(words, r.stream().map(row -> row.getString(0)).toList());
      }
    }
  }

  @Test
  void aDateGoesInAsTheDaysItIsAndComesBackAsADate() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Event", 2)) {
      frame.dates("on", ints(19782, 0));
      conn.register(frame);
      try (Result r = conn.query("MATCH (e:Event) RETURN e.on")) {
        Value.Temporal first = r.row(0).getTemporal(0);
        assertEquals(Value.Temporal.Kind.DATE, first.kind());
        assertEquals(19782L, first.count());
      }
    }
  }

  @Test
  void arrowMicrosecondsAreScaledToTheNanosecondsThisEngineCountsIn() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Event", 1)) {
      frame.timestamps("at", longs(1_700_000_000_000_000L));
      conn.register(frame);
      try (Result r = conn.query("MATCH (e:Event) RETURN e.at")) {
        Value.Temporal at = r.row(0).getTemporal(0);
        assertEquals(Value.Temporal.Kind.LOCAL_DATETIME, at.kind());
        assertEquals(1_700_000_000_000_000_000L, at.count());
      }
    }
  }

  @Test
  void oneFrameServesAsManyConnectionsAsThereAreThreadsToQueryFromIt() {
    try (Database db = Database.memory();
        Connection first = db.connect();
        Connection second = db.connect();
        Frame frame = Frame.of("Person", 2)) {
      frame.column("id", longs(10, 20));
      first.register(frame);
      second.register(frame);
      try (Result a = first.query("MATCH (p:Person) RETURN sum(p.id)");
          Result b = second.query("MATCH (p:Person) RETURN sum(p.id)")) {
        assertEquals(30L, a.row(0).getLong(0));
        assertEquals(30L, b.row(0).getLong(0));
      }
    }
  }

  @Test
  void theConnectionSaysWhatIsRegisteredOnIt() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame people = Frame.of("Person", 1);
        Frame places = Frame.of("Place", 1)) {
      people.column("id", longs(1));
      places.column("id", longs(2));
      assertEquals(0, conn.registeredCount());
      conn.register(people);
      conn.register(places);
      assertEquals(2, conn.registeredCount());
      assertEquals(List.of("Person", "Place"), conn.registeredNames());
      assertTrue(conn.unregister("Person"));
      assertEquals(List.of("Place"), conn.registeredNames());
      assertFalse(conn.unregister("Person"));
    }
  }

  @Test
  void theReleaseCallbackSaysWhenTheEngineIsFinished() throws InterruptedException {
    CountDownLatch released = new CountDownLatch(1);
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      try (Frame frame = Frame.of("Person", 2, released::countDown)) {
        frame.column("id", longs(1, 2));
        conn.register(frame);
        conn.query("MATCH (p:Person) RETURN p.id").close();
        assertEquals(1, released.getCount(), "nothing has let go of the columns yet");
        conn.unregister("Person");
      }
      assertTrue(
          released.await(5, TimeUnit.SECONDS), "the callback never ran after the frame went away");
    }
  }

  @Test
  void aBufferNothingElseRefersToIsNotCollectedOutFromUnderTheEngine() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 3)) {
      // Nothing but the frame refers to this buffer once the call returns, and
      // a direct buffer that becomes unreachable has its memory freed by a
      // cleaner. If the frame did not hold on to it the engine would be
      // pointing at memory that had been handed back.
      frame.column("id", longs(1, 2, 3));
      conn.register(frame);
      for (int i = 0; i < 10; i++) {
        System.gc();
        ByteBuffer.allocateDirect(1 << 20);
      }
      try (Result r = conn.query("MATCH (p:Person) RETURN sum(p.id)")) {
        assertEquals(6L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void aFrameNoConnectionEverSawStillLetsGoOfWhatItHeld() throws InterruptedException {
    CountDownLatch released = new CountDownLatch(1);
    try (Frame frame = Frame.of("Person", 1, released::countDown)) {
      frame.column("id", longs(1));
    }
    assertTrue(released.await(5, TimeUnit.SECONDS), "the callback never ran");
  }

  @Test
  void aHeapBufferIsRefusedRatherThanCopied() {
    try (Frame frame = Frame.of("Person", 3)) {
      ZuException e =
          assertThrows(ZuException.class, () -> frame.column("id", LongBuffer.wrap(new long[3])));
      assertTrue(e.getMessage().contains("allocateDirect"), e.getMessage());
    }
  }

  @Test
  void aColumnOfTheWrongLengthIsRefusedAtThatColumn() {
    try (Frame frame = Frame.of("Person", 3)) {
      assertThrows(ZuException.class, () -> frame.column("id", longs(1, 2)));
    }
  }

  @Test
  void aNameAStoredTableHoldsIsRefused() {
    Path path = dir.resolve("people.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 1);
      loader.column("id", 1L);
      loader.finish();
    }
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 1)) {
      frame.column("id", longs(9));
      assertThrows(ZuException.class, () -> conn.register(frame));
    }
  }

  @Test
  void aFrameReplacesAFrameOfTheSameName() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame first = Frame.of("Person", 1);
        Frame second = Frame.of("Person", 1)) {
      first.column("id", longs(1));
      second.column("id", longs(2));
      conn.register(first);
      conn.register(second);
      assertEquals(1, conn.registeredCount());
      try (Result r = conn.query("MATCH (p:Person) RETURN p.id")) {
        assertEquals(2L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void registeringInsideATransactionIsRefused() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 1)) {
      frame.column("id", longs(1));
      conn.begin();
      try {
        assertThrows(ZuException.class, () -> conn.register(frame));
      } finally {
        conn.rollback();
      }
    }
  }

  @Test
  void aStatementThatWouldWriteToAFrameIsRefused() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 1)) {
      frame.column("id", longs(1));
      conn.register(frame);
      assertThrows(ZuException.class, () -> conn.execute("MATCH (p:Person) SET p.id = 5"));
    }
  }

  @Test
  void aClosedFrameSaysSoRatherThanCrashing() {
    Frame frame = Frame.of("Person", 1);
    frame.close();
    assertTrue(frame.isClosed());
    assertThrows(ZuException.class, () -> frame.column("id", longs(1)));
  }

  @Test
  void aSliceOfABufferIsTheSliceAndNotTheWholeThing() {
    LongBuffer all = longs(1, 2, 3, 4, 5);
    all.position(1).limit(4);
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 3)) {
      frame.column("id", all.slice());
      conn.register(frame);
      try (Result r = conn.query("MATCH (p:Person) RETURN p.id AS id ORDER BY id")) {
        assertEquals(List.of(2L, 3L, 4L), r.stream().map(row -> row.getLong(0)).toList());
      }
    }
  }

  @Test
  void aFrameOfManyRowsIsReadWholeAndCorrectly() {
    int rows = 200_000;
    LongBuffer ids = direct(rows * 8).asLongBuffer();
    for (int i = 0; i < rows; i++) {
      ids.put(i);
    }
    ids.flip();
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", rows)) {
      frame.column("id", ids);
      conn.register(frame);
      try (Result r = conn.query("MATCH (p:Person) RETURN count(p), sum(p.id)")) {
        assertEquals(rows, r.row(0).getLong(0));
        assertEquals((long) rows * (rows - 1) / 2, r.row(0).getLong(1));
      }
    }
  }

  // ---- direct buffers, which is all a frame takes ----

  private static ByteBuffer direct(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }

  private static LongBuffer longs(long... values) {
    LongBuffer b = direct(values.length * 8).asLongBuffer();
    return b.put(values).flip();
  }

  private static IntBuffer ints(int... values) {
    IntBuffer b = direct(values.length * 4).asIntBuffer();
    return b.put(values).flip();
  }

  private static java.nio.ShortBuffer shorts(short... values) {
    java.nio.ShortBuffer b = direct(values.length * 2).asShortBuffer();
    return b.put(values).flip();
  }

  private static ByteBuffer bytes(byte... values) {
    ByteBuffer b = direct(values.length);
    return b.put(values).flip();
  }

  private static DoubleBuffer doubles(double... values) {
    DoubleBuffer b = direct(values.length * 8).asDoubleBuffer();
    return b.put(values).flip();
  }

  private static FloatBuffer floats(float... values) {
    FloatBuffer b = direct(values.length * 4).asFloatBuffer();
    return b.put(values).flip();
  }

  /** The characters of a column of strings, end to end, which is Arrow's data buffer. */
  private static ByteBuffer characters(List<String> words) {
    byte[] all =
        words.stream().collect(Collectors.joining()).getBytes(StandardCharsets.UTF_8);
    ByteBuffer b = direct(Math.max(all.length, 1));
    b.put(all).flip();
    return b;
  }

  private static IntBuffer offsets32(List<String> words) {
    IntBuffer b = direct((words.size() + 1) * 4).asIntBuffer();
    int at = 0;
    b.put(0);
    for (String w : words) {
      at += w.getBytes(StandardCharsets.UTF_8).length;
      b.put(at);
    }
    return b.flip();
  }

  private static LongBuffer offsets64(List<String> words) {
    LongBuffer b = direct((words.size() + 1) * 8).asLongBuffer();
    long at = 0;
    b.put(0);
    for (String w : words) {
      at += w.getBytes(StandardCharsets.UTF_8).length;
      b.put(at);
    }
    return b.flip();
  }

  /**
   * Arrow's Utf8View: four bytes of length, then either the characters
   * themselves when there are twelve or fewer of them, or a four byte prefix
   * and the buffer and offset they are at.
   */
  private static ByteBuffer views(List<String> words) {
    ByteBuffer b = direct(words.size() * 16);
    int at = 0;
    for (String w : words) {
      byte[] utf8 = w.getBytes(StandardCharsets.UTF_8);
      b.putInt(utf8.length);
      if (utf8.length <= 12) {
        b.put(utf8);
        for (int i = utf8.length; i < 12; i++) {
          b.put((byte) 0);
        }
      } else {
        b.put(utf8, 0, 4);
        b.putInt(0);
        b.putInt(at);
      }
      at += utf8.length;
    }
    return b.flip();
  }
}
