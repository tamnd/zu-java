package dev.zudb;

import dev.zudb.spi.ProviderUnavailableException;
import dev.zudb.spi.ZuBinding;
import dev.zudb.spi.ZuProvider;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The library itself: which provider bound it, which libzu it bound, and what
 * both of them call themselves.
 *
 * <p>Nothing here has to be called to use the client. {@link Database#open}
 * loads the library on its own, once, the first time anything needs it. This
 * is for the program that logs what it linked against, and for the bug report
 * that has to say.
 */
public final class Zu {

  /**
   * The revision of the C ABI this client was written against.
   *
   * <p>It is a constant rather than something read out of the library,
   * because the C ABI publishes its revision as a header macro and a macro is
   * not a symbol: a binding that never sees a C compiler has nothing to read
   * it from. What checks the two agree is a step in this repository's CI that
   * reads the macro out of the engine's own {@code zu.h}.
   *
   * <p>What the client does check at run time is that the library it loaded
   * has every symbol this client calls, which is the mismatch that actually
   * bites, and it names the missing one.
   */
  public static final String ABI_VERSION = "0.11";

  private static final Logger LOG = System.getLogger("dev.zudb");

  /** Names a provider to use rather than taking the highest priority that loads. */
  static final String PROVIDER_PROPERTY = "zu.provider";

  private Zu() {}

  /** Loaded on the first call that needs libzu, and not before. */
  private static final class Holder {
    static final Bound BOUND = bind();
  }

  record Bound(ZuBinding binding, String provider, Path library, String source) {}

  static ZuBinding binding() {
    return Holder.BOUND.binding();
  }

  /**
   * What the loaded libzu calls itself.
   *
   * @return the engine version, for example {@code "0.11.0"}
   */
  public static String version() {
    return Holder.BOUND.binding().version();
  }

  /**
   * Which provider bound the library.
   *
   * @return {@code "ffm"} or {@code "jni"}
   */
  public static String provider() {
    return Holder.BOUND.provider();
  }

  /**
   * Which file was loaded, which is the first question a bug report has to
   * answer.
   *
   * @return the path, which is a bare name when the platform was left to
   *     search for it
   */
  public static Path library() {
    return Holder.BOUND.library();
  }

  /**
   * Which of the four places it was found in, in the words the failure
   * message would have used.
   *
   * <p>This is the second question a bug report has to answer, and the one a
   * user cannot work out for themselves: a path is a path, and whether it came
   * from a property somebody set three shells ago or from a jar is the part
   * that explains why the wrong engine is loaded.
   *
   * @return a phrase, for example {@code "the zudb-native artifact,
   *     darwin-arm64"} or {@code "-Dzu.library"}
   */
  public static String source() {
    return Holder.BOUND.source();
  }

  private static Bound bind() {
    Library.Found found = Library.find();
    String wanted = System.getProperty(PROVIDER_PROPERTY);

    List<ZuProvider> providers = providers();
    if (wanted != null && !wanted.isBlank()) {
      providers.removeIf(p -> !p.name().equals(wanted));
      if (providers.isEmpty()) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(
                Status.MISUSE,
                "-D"
                    + PROVIDER_PROPERTY
                    + "="
                    + wanted
                    + " names no provider on this classpath; "
                    + "the two this client ships are ffm and jni"));
      }
    }
    providers.sort(Comparator.comparingInt(ZuProvider::priority).reversed());

    List<String> refused = new ArrayList<>();
    for (ZuProvider p : providers) {
      try {
        ZuBinding binding = p.load(found.path());
        LOG.log(
            Level.DEBUG,
            () ->
                "zu "
                    + binding.version()
                    + " through the "
                    + p.name()
                    + " provider, from "
                    + found.path()
                    + " by way of "
                    + found.source());
        return new Bound(binding, p.name(), found.path(), found.source());
      } catch (ProviderUnavailableException e) {
        refused.add(p.name() + ": " + e.getMessage());
      }
    }

    throw new ZuProgrammingException(
        Diagnostic.misuse(Status.MISUSE, unavailable(found, refused)));
  }

  private static String unavailable(Library.Found found, List<String> refused) {
    StringBuilder sb = new StringBuilder();
    sb.append("no provider could bind libzu at ")
        .append(found.path())
        .append(", found through ")
        .append(found.source())
        .append(". ");
    if (refused.isEmpty()) {
      sb.append(
          "There is no provider on the classpath at all: add dev.zudb:zudb-ffm for JDK 22 "
              + "and later, or dev.zudb:zudb-jni for 17 and later. The zudb artifact is the "
              + "API and binds nothing on its own.");
    } else {
      sb.append("Every provider refused. ").append(String.join("; ", refused));
    }
    // What was ruled out on the way here, in order, because the last place
    // searched is the least informative one to be told about: a user whose
    // artifact is on the module path but unresolved needs to hear that and
    // not that java.library.path has no libzu in it.
    if (!found.looked().isEmpty()) {
      sb.append(" Before that: ").append(String.join("; then ", found.looked())).append(".");
    }
    sb.append(" Set -D").append(Library.PROPERTY).append(" to point at a libzu of your own.");
    return sb.toString();
  }

  /**
   * Every provider on the classpath that this JVM can load.
   *
   * <p>A provider compiled for a newer release than this JVM is a
   * {@link ServiceConfigurationError} at the moment it is instantiated, and
   * that is the ordinary case rather than a broken one: the Panama provider
   * is compiled for 25 and both artifacts are on the classpath of a program
   * running on 17. So each is resolved on its own and a failure to load one
   * is a provider that is not there, which is exactly what it is.
   */
  private static List<ZuProvider> providers() {
    List<ZuProvider> found = new ArrayList<>();
    load(Zu.class.getClassLoader(), found);
    if (found.isEmpty()) {
      load(Thread.currentThread().getContextClassLoader(), found);
    }
    return found;
  }

  private static void load(ClassLoader loader, List<ZuProvider> into) {
    if (loader == null) {
      return;
    }
    for (ServiceLoader.Provider<ZuProvider> p :
        ServiceLoader.load(ZuProvider.class, loader).stream().toList()) {
      try {
        into.add(p.get());
      } catch (ServiceConfigurationError e) {
        LOG.log(Level.TRACE, () -> "a zu provider did not load on this JVM: " + e.getMessage());
      }
    }
  }

  /**
   * The provider that would be used, without loading the library.
   *
   * <p>For a program that wants to say what it is about to do, and for a test
   * that has to know whether the Panama path is even on this JVM.
   *
   * @return the name of the highest-priority provider on the classpath, or
   *     empty if there is none
   */
  public static Optional<String> availableProvider() {
    return providers().stream().max(Comparator.comparingInt(ZuProvider::priority))
        .map(ZuProvider::name);
  }
}
