package dev.zudb.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * What a provider needs to know about the machine it is on.
 *
 * <p>Two of the three questions here are asked twice in this repository: the
 * API module asks them to find libzu, and the JNI provider asks them again to
 * find its own shim, which is a second library and a different file with the
 * same seven platforms under it. They are answered here so that the two
 * cannot come to disagree about what a platform is called, which would show up
 * as a jar that has the library for the machine it is on and cannot find it.
 *
 * <p>Like everything else in this package, this is not part of the supported
 * surface.
 */
public final class Natives {

  private Natives() {}

  /**
   * The name this client gives the operating system and the instruction set,
   * which is Go's spelling of both, because that is what the library
   * artifacts in every other client of this engine are named after.
   *
   * @return for example {@code darwin-arm64}
   */
  public static String platform() {
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
   * The same, and which C library on the platform where there are two.
   *
   * <p>Alpine is not a smaller Linux, it is a different one: a shared object
   * built against glibc does not load on musl and says so in a message about
   * an interpreter rather than about a database. The two builds are two
   * artifacts everywhere else this engine ships, so they are two here as well,
   * and the choice is made by looking for musl's own loader, which is the one
   * file whose path is fixed by the ABI rather than by a distribution.
   *
   * <p>Nowhere but Linux has a second answer, so nowhere but Linux is asked.
   *
   * @return for example {@code darwin-arm64} or {@code linux-amd64-musl}
   */
  public static String flavour() {
    String platform = platform();
    if (!platform.startsWith("linux-")) {
      return platform;
    }
    return musl() ? platform + "-musl" : platform;
  }

  /** Whether this is a musl system, by its loader rather than by its name. */
  private static boolean musl() {
    for (String loader : new String[] {"/lib/ld-musl-x86_64.so.1", "/lib/ld-musl-aarch64.so.1"}) {
      if (Files.exists(Paths.get(loader))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Copies a library out of the classpath, because a library inside a jar is
   * not a file and every loader on every platform wants a file.
   *
   * @param resource where it is
   * @param name what to call the copy, which matters because a loader reports
   *     the file name it failed on and a temporary name would be no help
   * @param near a class of the artifact the resource is in, whose loader is
   *     the one asked, because the API module and the JNI provider are two
   *     artifacts and on a module path they are two loaders as well
   * @return the copy, or null if there is no such resource
   */
  public static Path unpack(String resource, String name, Class<?> near) {
    ClassLoader loader = near.getClassLoader();
    try (InputStream in =
        loader == null
            ? ClassLoader.getSystemResourceAsStream(resource)
            : loader.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      Path dir = Files.createTempDirectory("zudb");
      Path file = dir.resolve(name);
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
