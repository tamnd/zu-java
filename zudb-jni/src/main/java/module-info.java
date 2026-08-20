/**
 * The zu provider over JNI, for the JDKs that have no Panama.
 *
 * <p>Nothing here is exported, the same as the Panama provider: a program
 * depends on this module to have it, not to name it, and what it gets is a
 * service {@code dev.zudb} finds on its own. Which of the two providers a
 * program ended up on is a log line rather than a compile-time fact, which is
 * the point of both.
 */
module dev.zudb.jni {
  requires dev.zudb;

  provides dev.zudb.spi.ZuProvider with
      dev.zudb.jni.JniProvider;
}
