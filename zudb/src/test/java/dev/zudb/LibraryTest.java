package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.spi.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    String platform = Natives.platform();
    assertTrue(
        platform.matches("(darwin|linux|windows|[a-z0-9]+)-(amd64|arm64|[a-z0-9_]+)"),
        platform + " is not a goos-goarch pair");
    assertEquals(2, platform.split("-").length);
  }

  @Test
  void theCLibraryIsPartOfTheAnswerOnLinuxAndNowhereElse() {
    // A shared object built against glibc does not load on musl, so the two
    // are two artifacts. Everywhere else there is one C library and nothing
    // to say about it.
    String platform = Natives.platform();
    String flavour = Natives.flavour();
    if (platform.startsWith("linux-")) {
      assertTrue(
          flavour.equals(platform) || flavour.equals(platform + "-musl"),
          flavour + " is not " + platform + " with or without musl after it");
    } else {
      assertEquals(platform, flavour);
    }
  }

  @Test
  void aLibraryInAJarBecomesAFileWithTheSameBytes() throws Exception {
    // What the zudb-native artifact holds is a resource, and no loader on any
    // platform can map one of those. The stand-in under test resources is not
    // a library, deliberately: what is being checked is the copy, and a real
    // one would only make the test slower and platform-specific.
    String resource = "dev/zudb/native/a-platform-that-is-not-one/libzu.stand-in";
    Path unpacked = Natives.unpack(resource, "libzu.stand-in", Library.class);
    assertTrue(unpacked != null, "the stand-in is not on the test classpath");
    assertTrue(Files.isRegularFile(unpacked));
    byte[] want;
    try (var in = LibraryTest.class.getClassLoader().getResourceAsStream(resource)) {
      want = in.readAllBytes();
    }
    assertArrayEquals(want, Files.readAllBytes(unpacked));
    // A directory of its own each time, so two callers cannot land on one
    // file and so a copy cannot be made over a library already mapped.
    Path again = Natives.unpack(resource, "libzu.stand-in", Library.class);
    assertNotEquals(unpacked, again);
  }

  @Test
  void theSearchSaysWhatItRuledOutOnTheWay() {
    // The four places in order, and what is reported is however many of them
    // were ruled out before one answered. How many that is depends on the
    // machine this runs on, which is the point: the list is what tells a user
    // what was tried, and the order is what makes it readable.
    List<String> places =
        List.of("-Dzu.library", "ZU_LIBRARY", "a zudb-native artifact", "the platform library");
    Library.Found found = Library.find();
    List<String> looked = found.looked();
    assertTrue(looked.size() < places.size(), "everything was ruled out and something was found");
    for (int i = 0; i < looked.size(); i++) {
      assertTrue(
          looked.get(i).startsWith(places.get(i)),
          looked.get(i) + " is not the place that comes " + (i + 1) + "th");
    }
    assertFalse(
        looked.contains(found.source()), "the place it was found is listed as one it was not");
  }

  @Test
  void aPlatformWithNoArtifactIsNotAFailure() {
    // The classpath is the third of four places, so nothing there means the
    // search carries on to the platform's own rather than stopping.
    assertNull(Natives.unpack("dev/zudb/native/vax-11-780/libzu.so", "libzu.so", Library.class));
  }

  private static void restore(String before) {
    if (before == null) {
      System.clearProperty(Library.PROPERTY);
    } else {
      System.setProperty(Library.PROPERTY, before);
    }
  }
}
