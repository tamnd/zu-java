package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Config;
import dev.zudb.Connection;
import dev.zudb.Result;
import dev.zudb.ZuException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-call openers, and a configuration that arrives as text.
 *
 * <p>Neither of these is a new thing the engine can do. They are the same
 * calls with less to write around them, which is the whole of what they are
 * for: a script that wants one connection should not have to close two
 * objects, and a program reading its settings out of a file should not have to
 * know which of three fields a key lands in.
 */
public class ShorthandTest {

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void aConnectionInMemoryNeedsNothingAroundIt() {
    try (Connection conn = Connection.memory();
        Result r = conn.query("RETURN 1 AS one")) {
      assertEquals(1L, r.row(0).getLong(0));
    }
  }

  @Test
  void aConnectionInMemoryCanBeDuplicatedOntoTheSameGraph() {
    try (Connection first = Connection.memory();
        Connection second = first.duplicate();
        Result r = second.query("RETURN 1 AS one")) {
      assertEquals(1L, r.row(0).getLong(0));
      assertFalse(first.isClosed());
    }
  }

  @Test
  void aConnectionOnAFileMakesItAndOpensItAgain(@TempDir Path dir) {
    Path file = dir.resolve("graph.zu");
    try (Connection conn = Connection.create(file)) {
      assertFalse(conn.isClosed());
    }
    assertTrue(Files.isRegularFile(file));
    try (Connection conn = Connection.open(file);
        Result r = conn.query("RETURN 2 AS two")) {
      assertEquals(2L, r.row(0).getLong(0));
    }
  }

  @Test
  void creatingOverSomethingAlreadyThereIsRefused(@TempDir Path dir) {
    Path file = dir.resolve("graph.zu");
    Connection.create(file).close();
    assertThrows(ZuException.class, () -> Connection.create(file).close());
  }

  @Test
  void openingSomethingThatIsNotThereIsRefused(@TempDir Path dir) {
    assertThrows(ZuException.class, () -> Connection.open(dir.resolve("nothing.zu")).close());
  }

  @Test
  void aStringIsTakenWhereAPathIs(@TempDir Path dir) {
    String file = dir.resolve("graph.zu").toString();
    Connection.create(file).close();
    Connection.open(file).close();
  }

  @Test
  void anOptionIsSetByName() {
    assertEquals(1 << 20, Config.defaults().with("memory_limit", "1048576").memoryLimit());
    assertEquals(4, Config.defaults().with("threads", "4").threads());
    assertTrue(Config.defaults().with("read_only", "true").readOnly());
    assertTrue(Config.defaults().with("read_only", "1").readOnly());
    assertFalse(Config.defaults().withReadOnly(true).with("read_only", "false").readOnly());
  }

  @Test
  void settingOneOptionLeavesTheOthersWhereTheyWere() {
    Config config = Config.defaults().withThreads(2).withMemoryLimit(1 << 20);
    Config after = config.with("read_only", "true");
    assertEquals(2, after.threads());
    assertEquals(1 << 20, after.memoryLimit());
    assertTrue(after.readOnly());
  }

  @Test
  void aWholeMapIsTakenAtOnce() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("threads", "1");
    options.put("memory_limit", "2097152");
    options.put("read_only", "false");
    Config config = Config.of(options);
    assertEquals(1, config.threads());
    assertEquals(2 << 20, config.memoryLimit());
    assertFalse(config.readOnly());
    assertEquals(Config.defaults(), Config.of(Map.of()));
  }

  @Test
  void aKeyTheEngineDoesNotKnowIsRefusedAndNamed() {
    ZuException e =
        assertThrows(ZuException.class, () -> Config.defaults().with("thread_count", "4"));
    assertTrue(
        e.getMessage().contains("thread_count"),
        "the message did not say which key was the typo: " + e.getMessage());
  }

  @Test
  void aValueTheKeyCannotTakeIsRefused() {
    assertThrows(ZuException.class, () -> Config.defaults().with("threads", "lots"));
    assertThrows(ZuException.class, () -> Config.defaults().with("memory_limit", "512MB"));
    assertThrows(ZuException.class, () -> Config.defaults().with("read_only", "yes"));
  }

  @Test
  void anOptionSetByNameIsAnOptionTheDatabaseIsOpenedWith() {
    try (dev.zudb.Database db = dev.zudb.Database.memory(Config.of(Map.of("threads", "1")));
        Connection conn = db.connect();
        Result r = conn.query("RETURN 3 AS three")) {
      assertEquals(3L, r.row(0).getLong(0));
    }
  }
}
