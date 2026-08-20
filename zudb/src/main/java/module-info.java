/**
 * The zu client for Java.
 *
 * <p>This module is the whole API and none of the native access. What talks to
 * the library is a provider, found at run time through {@link
 * dev.zudb.spi.ZuProvider}, so that a program on JDK 25 gets the Panama one and
 * a program on 17 gets the JNI one without either of them being on the
 * compile-time path of the other.
 */
module dev.zudb {
  exports dev.zudb;
  exports dev.zudb.spi;

  uses dev.zudb.spi.ZuProvider;
}
