package dev.zudb.jni;

import dev.zudb.spi.Natives;
import dev.zudb.spi.ProviderUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds and loads the native shim, once.
 *
 * <p>The shim is the second library this provider needs, and it is not libzu.
 * libzu is the engine, it is large, and the API module already knows four
 * places to look for it. The shim is a few tens of kilobytes of C that turns
 * a JNI call into a call through a function pointer, one build per platform,
 * and it ships inside this artifact for the platforms that are tier one.
 *
 * <p>The order below is the API module's order for libzu, for the same
 * reasons and with the same escape hatch first: somebody who built their own
 * shim, or who is running one out of a build directory, names it and is
 * believed. Only then does the jar's own copy get used, and only then the
 * platform's search, which is what a distribution packaging this would want.
 *
 * <p>Loading is done here rather than in a static initialiser on the binding
 * because a failure in a static initialiser is an
 * {@link ExceptionInInitializerError} the first time and a bare
 * {@link NoClassDefFoundError} every time after, and neither says which of
 * four places was looked in. This throws
 * {@link ProviderUnavailableException} with all four.
 */
final class Shim {

  private Shim() {}

  /** What the shim is called, before the platform decorates it. */
  private static final String NAME = "zudb_jni";

  private static boolean loaded;

  /**
   * Loads the shim and binds it to the binding, or says why it could not.
   *
   * <p>Called under the class lock, which is enough: the work is done once
   * and everything after it is a read of a boolean that the same lock
   * published.
   *
   * @throws ProviderUnavailableException if there is no shim for this machine
   */
  static synchronized void ensure() {
    if (loaded) {
      return;
    }
    List<String> looked = new ArrayList<>();
    Path file = named(System.getProperty("zu.jni.library"), "the zu.jni.library property", looked);
    if (file == null) {
      file = named(System.getenv("ZU_JNI_LIBRARY"), "the ZU_JNI_LIBRARY variable", looked);
    }

    String library = System.mapLibraryName(NAME);
    if (file == null) {
      String resource = "dev/zudb/jni/" + Natives.flavour() + "/" + library;
      file = Natives.unpack(resource, library, Shim.class);
      if (file == null) {
        looked.add("no " + resource + " on the classpath");
      }
    }

    try {
      if (file != null) {
        System.load(file.toAbsolutePath().toString());
      } else {
        // The last resort, and the one a distribution that packages this
        // properly would rely on: LD_LIBRARY_PATH, /usr/local/lib,
        // java.library.path, whatever this platform calls its search.
        System.loadLibrary(NAME);
      }
    } catch (UnsatisfiedLinkError e) {
      if (file == null) {
        looked.add("no " + library + " on java.library.path: " + e.getMessage());
      } else {
        looked.add(file + " did not load: " + e.getMessage());
      }
      throw new ProviderUnavailableException(
          "the JNI provider needs its native shim, and " + String.join(", and ", looked), e);
    }

    if (!JniBinding.nRegister()) {
      throw new ProviderUnavailableException(
          "the JNI shim loaded and then could not bind itself to "
              + JniBinding.class.getName()
              + ", which means the shim and this jar are from different releases");
    }
    loaded = true;
  }

  /** A path somebody named, checked for being there so the reason is theirs. */
  private static Path named(String value, String where, List<String> looked) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    Path path = Paths.get(value);
    if (Files.isRegularFile(path)) {
      return path;
    }
    looked.add(where + " names " + value + ", which is not a file");
    return null;
  }
}
