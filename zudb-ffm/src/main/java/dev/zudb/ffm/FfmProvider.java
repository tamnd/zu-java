package dev.zudb.ffm;

import dev.zudb.spi.ProviderUnavailableException;
import dev.zudb.spi.ZuBinding;
import dev.zudb.spi.ZuProvider;
import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * The provider that binds zu through the Foreign Function and Memory API.
 *
 * <p>This is the one to have. There is no C compilation step, no second
 * artifact per platform, and no JNI stub between the call and the library:
 * a downcall handle is a direct call once it has been compiled. It needs
 * JDK 25, which is what this artifact is built for.
 */
public final class FfmProvider implements ZuProvider {

  /**
   * What the service loader calls.
   *
   * <p>Public and taking nothing because {@link java.util.ServiceLoader} says
   * so. Nothing else has a reason to make one.
   */
  public FfmProvider() {}

  @Override
  public String name() {
    return "ffm";
  }

  @Override
  public int priority() {
    return 100;
  }

  @Override
  public ZuBinding load(Path library) {
    Module module = FfmProvider.class.getModule();
    if (!module.isNativeAccessEnabled()) {
      throw new ProviderUnavailableException(
          "this JVM has not granted native access to "
              + (module.isNamed() ? module.getName() : "the class path")
              + ", so it cannot call libzu: start it with --enable-native-access="
              + (module.isNamed() ? module.getName() : "ALL-UNNAMED")
              + ", or put Enable-Native-Access: ALL-UNNAMED in the manifest of the jar"
              + " that starts the process");
    }
    // The arena is global on purpose. A library unloaded while a database is
    // still open is a segfault, not an exception, and nothing in this API
    // hands out a moment at which every handle is known to be gone.
    return new FfmBinding(new Abi(library, Arena.global()));
  }
}
