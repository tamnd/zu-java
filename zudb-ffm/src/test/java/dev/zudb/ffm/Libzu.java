package dev.zudb.ffm;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Whether there is a libzu to test against, and where.
 *
 * <p>These tests link against a real engine, so they are skipped rather than
 * failed when there is not one to link against. A checkout with no build of
 * the engine beside it is an ordinary state for this repository to be in, and
 * a red suite for it would train everybody to ignore a red suite.
 *
 * <p>Point them at one with {@code -Dzu.library=/path/to/libzu.dylib}, or set
 * {@code ZU_LIBRARY}. A sibling checkout of the engine with a release build in
 * it is found on its own.
 */
final class Libzu {

  private Libzu() {}

  private static final Path FOUND = locate();

  /** Skips the calling test when there is no engine to call. */
  static void require() {
    assumeTrue(FOUND != null, "no libzu: set -Dzu.library to run these");
    if (System.getProperty("zu.library") == null) {
      System.setProperty("zu.library", FOUND.toString());
    }
  }

  private static Path locate() {
    String named = System.getProperty("zu.library");
    if (named == null || named.isBlank()) {
      named = System.getenv("ZU_LIBRARY");
    }
    if (named != null && !named.isBlank()) {
      Path p = Paths.get(named);
      return Files.isRegularFile(p) ? p : null;
    }
    String name = System.mapLibraryName("zu");
    // Up out of zudb-ffm, out of the repository, and into whichever
    // checkout of the engine is beside it.
    Path here = Paths.get("").toAbsolutePath();
    for (Path root = here; root != null; root = root.getParent()) {
      for (String sibling : new String[] {"zu", "zu-dx", "zu-g0"}) {
        Path candidate = root.resolveSibling(sibling).resolve("target/release").resolve(name);
        if (Files.isRegularFile(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }
}
