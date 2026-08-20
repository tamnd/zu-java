package dev.zudb;

import dev.zudb.spi.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Where libzu is.
 *
 * <p>Four places, in this order, and the first that answers wins. A path
 * somebody named is first because a bisect and a bug report both start by
 * pointing this at a build; the artifact that ships with the client is next
 * because it is what a user who installed nothing has; and the platform's own
 * search is last because it is the one that can pick up a library from
 * somewhere nobody in this process chose.
 *
 * <p>Finding it happens here rather than in a provider, so that the two
 * providers cannot disagree about which library they loaded and so that the
 * answer can be printed.
 */
final class Library {

  /** A path to the library itself, which wins over everything else. */
  static final String PROPERTY = "zu.library";

  /** The same, for a process that cannot pass a system property. */
  static final String ENVIRONMENT = "ZU_LIBRARY";

  private Library() {}

  /** The module the library artifacts name themselves, on the module path. */
  static final String NATIVE_MODULE = "dev.zudb.natives";

  /**
   * The library, and how it was found.
   *
   * @param path the file, or a bare name meaning that the platform is to
   *     search for it
   * @param source a phrase naming where the path came from, for the log line
   *     and for a failure
   * @param looked the places tried before this one, in order, so that a
   *     failure can say what was ruled out rather than only what was left
   */
  record Found(Path path, String source, List<String> looked) {}

  static Found find() {
    List<String> looked = new ArrayList<>();

    String named = System.getProperty(PROPERTY);
    if (named != null && !named.isBlank()) {
      Path p = Paths.get(named);
      if (!Files.isRegularFile(p)) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(
                Status.MISUSE, "-D" + PROPERTY + "=" + named + " names no file"));
      }
      return new Found(p, "-D" + PROPERTY, looked);
    }
    looked.add("-D" + PROPERTY);

    String env = System.getenv(ENVIRONMENT);
    if (env != null && !env.isBlank()) {
      Path p = Paths.get(env);
      if (!Files.isRegularFile(p)) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(Status.MISUSE, ENVIRONMENT + "=" + env + " names no file"));
      }
      return new Found(p, ENVIRONMENT, looked);
    }
    looked.add(ENVIRONMENT);

    String flavour = Natives.flavour();
    String library = System.mapLibraryName("zu");
    String resource = "dev/zudb/native/" + flavour + "/" + library;
    Path unpacked = Natives.unpack(resource, library, Library.class);
    if (unpacked != null) {
      return new Found(unpacked, "the zudb-native artifact, " + flavour, looked);
    }
    looked.add(
        "a zudb-native artifact for "
            + flavour
            + ", which was not on the classpath"
            + (Library.class.getModule().isNamed() && ModuleLayer.boot().findModule(
                    NATIVE_MODULE).isEmpty()
                ? ". This is a module path, and nothing requires that artifact, so a jar sitting"
                    + " on the path is not resolved and its library is not visible: add"
                    + " --add-modules " + NATIVE_MODULE
                : ""));

    // A bare name, which is the platform being asked to search:
    // java.library.path, and then whatever the loader does after that.
    return new Found(
        Paths.get(System.mapLibraryName("zu")), "the platform library path", looked);
  }

}
