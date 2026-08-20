package dev.zudb.spi;

import java.nio.file.Path;

/**
 * How a {@link ZuBinding} gets made, and the service the API module looks up
 * with {@link java.util.ServiceLoader}.
 *
 * <p>There are two in this repository. The Panama one binds through the
 * Foreign Function and Memory API and needs a recent JDK; the JNI one binds
 * through a small native shim and runs on 17. A user names neither: the API
 * module takes the highest {@link #priority()} that loads, and says which it
 * took once, at debug level.
 *
 * <p>Finding the library is not a provider's job. The API module resolves one
 * path, from a system property, an environment variable, a native artifact on
 * the classpath, or the platform's own search, and hands the same path to
 * whichever provider it tries.
 */
public interface ZuProvider {

  /**
   * What to call this provider in a log line and in a failure.
   *
   * @return a short name, {@code "ffm"} or {@code "jni"}
   */
  String name();

  /**
   * Which provider wins when more than one loads. Higher goes first.
   *
   * <p>The two in this repository are 100 for Panama and 50 for JNI, spaced
   * so that something else can be put between them without either moving.
   *
   * @return the priority
   */
  int priority();

  /**
   * Loads the library and binds every call in it.
   *
   * <p>This is where a provider decides it cannot run: a JVM too old for the
   * API it needs, native access not granted, a library that is missing a
   * symbol this client calls. All three are
   * {@link ProviderUnavailableException}, which is not a failure of the
   * program but a fact about this JVM, and the API module tries the next
   * provider and reports every reason if none is left.
   *
   * @param library the library to load
   * @return a binding over it, never null
   * @throws ProviderUnavailableException if this provider cannot run here
   */
  ZuBinding load(Path library);
}
