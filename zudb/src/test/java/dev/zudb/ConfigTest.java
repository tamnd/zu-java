package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The three knobs a database is opened with. */
class ConfigTest {

  @Test
  void theDefaultsAreZeroesAndZeroMeansTheEngineDecides() {
    Config c = Config.defaults();
    assertEquals(0, c.memoryLimit());
    assertEquals(0, c.threads());
    assertFalse(c.readOnly());
  }

  @Test
  void theWithersChangeOneFieldEach() {
    Config c = Config.defaults().withMemoryLimit(1 << 20).withThreads(1).withReadOnly(true);
    assertEquals(1 << 20, c.memoryLimit());
    assertEquals(1, c.threads());
    assertTrue(c.readOnly());
  }

  @Test
  void aNegativeCountIsRefusedWhereItIsWrittenRatherThanAtTheOpen() {
    assertThrows(ZuProgrammingException.class, () -> Config.defaults().withMemoryLimit(-1));
    assertThrows(ZuProgrammingException.class, () -> Config.defaults().withThreads(-1));
  }

  @Test
  void twoConfigurationsWithTheSameFieldsAreOneValue() {
    assertEquals(Config.defaults().withThreads(4), new Config(0, 4, false));
  }
}
