package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Zu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What loaded, and what it says it is. */
public class ZuTest {

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void theProviderIsTheOneThisRunNamed() {
    // These cases are run once per provider and the run says which, so
    // this checks that the naming took rather than that a particular one
    // won. A run in which it did not take would be a run of the same
    // provider twice, which is the failure this catches.
    String named = System.getProperty("zu.provider", "ffm");
    assertEquals(named, Zu.availableProvider().orElseThrow());
    assertEquals(named, Zu.provider());
  }

  @Test
  void theEngineSaysWhatVersionItIs() {
    // The release version of the engine, which moves on its own and is not
    // the ABI version. A client checks the ABI version, a bug report quotes
    // this one.
    String version = Zu.version();
    assertFalse(version.isBlank());
    assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), version);
  }

  @Test
  void theClientSaysWhichAbiItSpeaks() {
    // Hard-coded rather than read out of the library, because the ABI
    // version is a header macro and a binding with no C compile step has
    // nowhere to read it from. CI checks it against the engine's zu.h.
    assertTrue(Zu.ABI_VERSION.matches("\\d+\\.\\d+"), Zu.ABI_VERSION);
  }

  @Test
  void theLoadedFileIsNamed() {
    // The first question a bug report has to answer.
    assertTrue(Zu.library().toString().contains("zu"));
  }
}
