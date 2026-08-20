package dev.zudb.jni;

import dev.zudb.spi.ProviderUnavailableException;
import dev.zudb.spi.ZuBinding;
import dev.zudb.spi.ZuProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The provider that binds zu through JNI.
 *
 * <p>This is the one for JDK 17 through 21, which have no Foreign Function
 * and Memory API and are most of what is running in production. It costs a
 * native shim per platform, which this artifact carries, and it is a little
 * slower on the string paths than Panama is. Everything else about it is the
 * same: the same handles, the same failures, the same buffers over the
 * engine's own memory.
 *
 * <p>Its priority is below Panama's, so a JDK that has both takes Panama and
 * this one is never loaded. Nothing chooses it by name.
 */
public final class JniProvider implements ZuProvider {

  /**
   * What the service loader calls.
   *
   * <p>Public and taking nothing because {@link java.util.ServiceLoader} says
   * so. Nothing else has a reason to make one.
   */
  public JniProvider() {}

  @Override
  public String name() {
    return "jni";
  }

  @Override
  public int priority() {
    return 50;
  }

  @Override
  public ZuBinding load(Path library) {
    Shim.ensure();
    byte[] why = JniBinding.nLoad(library.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    if (why != null) {
      throw new ProviderUnavailableException(new String(why, StandardCharsets.UTF_8));
    }
    return new JniBinding();
  }
}
