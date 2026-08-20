package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where the library is looked for, and in which order. */
class LibraryTest {

  @Test
  void aNamedPathWins(@TempDir Path dir) throws Exception {
    Path fake = Files.createFile(dir.resolve(System.mapLibraryName("zu")));
    String before = System.getProperty(Library.PROPERTY);
    System.setProperty(Library.PROPERTY, fake.toString());
    try {
      Library.Found found = Library.find();
      assertEquals(fake, found.path());
      assertEquals("-Dzu.library", found.source());
    } finally {
      restore(before);
    }
  }

  @Test
  void aNamedPathThatIsNotThereFailsWhereItWasWrittenDown() {
    String before = System.getProperty(Library.PROPERTY);
    System.setProperty(Library.PROPERTY, "/no/such/libzu.dylib");
    try {
      ZuProgrammingException e = assertThrows(ZuProgrammingException.class, Library::find);
      assertTrue(e.getMessage().contains("/no/such/libzu.dylib"));
    } finally {
      restore(before);
    }
  }

  @Test
  void thePlatformIsSpelledTheWayTheArtifactsAre() {
    // Go's spelling, because every library artifact this engine publishes is
    // named after it, and two spellings of one platform is how a client ends
    // up unable to find its own jar.
    String platform = Library.platform();
    assertTrue(
        platform.matches("(darwin|linux|windows|[a-z0-9]+)-(amd64|arm64|[a-z0-9_]+)"),
        platform + " is not a goos-goarch pair");
    assertEquals(2, platform.split("-").length);
  }

  private static void restore(String before) {
    if (before == null) {
      System.clearProperty(Library.PROPERTY);
    } else {
      System.setProperty(Library.PROPERTY, before);
    }
  }
}
