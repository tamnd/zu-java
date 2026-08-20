/**
 * The zu provider over the Foreign Function and Memory API.
 *
 * <p>Nothing here is exported. A program depends on this module to have it,
 * not to name it, and what it gets is a service {@code dev.zudb} finds on its
 * own. That also keeps every {@code java.lang.foreign} type out of anything a
 * user writes, which is what lets the same user code run on the JNI provider
 * on JDK 17.
 */
module dev.zudb.ffm {
  requires dev.zudb;

  provides dev.zudb.spi.ZuProvider with
      dev.zudb.ffm.FfmProvider;
}
