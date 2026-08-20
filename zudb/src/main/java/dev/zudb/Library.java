package dev.zudb;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

  /**
   * The library, and how it was found.
   *
   * @param path the file, or a bare name meaning that the platform is to
   *     search for it
   * @param source a phrase naming where the path came from, for the log line
   *     and for a failure
   */
  record Found(Path path, String source) {}

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
      return new Found(p, "-D" + PROPERTY);
    }
    looked.add("-D" + PROPERTY);

    String env = System.getenv(ENVIRONMENT);
    if (env != null && !env.isBlank()) {
      Path p = Paths.get(env);
      if (!Files.isRegularFile(p)) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(Status.MISUSE, ENVIRONMENT + "=" + env + " names no file"));
      }
      return new Found(p, ENVIRONMENT);
    }
    looked.add(ENVIRONMENT);

    String resource = "dev/zudb/native/" + platform() + "/" + System.mapLibraryName("zu");
    Path unpacked = unpack(resource);
    if (unpacked != null) {
      return new Found(unpacked, "the zudb-native-" + platform() + " artifact");
    }
    looked.add("a zudb-native-" + platform() + " artifact on the classpath");

    // A bare name, which is the platform being asked to search:
    // java.library.path, and then whatever the loader does after that.
    return new Found(Paths.get(System.mapLibraryName("zu")), "the platform library path");
  }

  /**
   * The name this client gives the operating system and the instruction set,
   * which is Go's spelling of both, because that is what the library
   * artifacts in every other client of this engine are named after.
   *
   * @return for example {@code darwin-arm64}
   */
  static String platform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

    String goos;
    if (os.startsWith("mac") || os.startsWith("darwin")) {
      goos = "darwin";
    } else if (os.startsWith("win")) {
      goos = "windows";
    } else if (os.startsWith("linux")) {
      goos = "linux";
    } else {
      goos = os.split("\\s")[0];
    }

    String goarch;
    if (arch.equals("x86_64") || arch.equals("amd64")) {
      goarch = "amd64";
    } else if (arch.equals("aarch64") || arch.equals("arm64")) {
      goarch = "arm64";
    } else {
      goarch = arch;
    }

    return goos + "-" + goarch;
  }

  /**
   * Copies a library out of the classpath, because a library inside a jar is
   * not a file and every loader on every platform wants a file.
   *
   * @param resource where it is
   * @return the copy, or null if there is no such resource
   */
  private static Path unpack(String resource) {
    ClassLoader loader = Library.class.getClassLoader();
    try (InputStream in =
        loader == null
            ? ClassLoader.getSystemResourceAsStream(resource)
            : loader.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      Path dir = Files.createTempDirectory("zudb");
      Path file = dir.resolve(System.mapLibraryName("zu"));
      Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
      // Best effort, and it fails on Windows for a library still mapped
      // into the process. A file in the temp directory is what the
      // operating system already cleans up after.
      file.toFile().deleteOnExit();
      dir.toFile().deleteOnExit();
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("could not unpack " + resource, e);
    }
  }
}
