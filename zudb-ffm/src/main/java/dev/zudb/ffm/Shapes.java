package dev.zudb.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every shape of call that crosses the boundary, remembered as it is bound.
 *
 * <p>This exists for one reader, and it is not a person. Ahead-of-time
 * compilation cannot see a downcall coming: a stub for a given signature is
 * machine code that has to be generated while the image is being built, and
 * the builder has no way of knowing which signatures a program will ask for,
 * because a {@link FunctionDescriptor} is assembled at run time out of
 * ordinary objects. So the signatures are written down in a file the image
 * builder reads, and the file has to say exactly what this binding does.
 *
 * <p>Writing that file by hand is how it goes wrong. Forty-odd entries nobody
 * looks at, one of them stale, and the failure is a native image that builds
 * clean and dies on a call that a JVM run makes every time. So it is not
 * written by hand: every binding registers its shape here as it is made, a
 * test builds the file from what was registered, and the build fails if the
 * file in the repository is not that. The registry is a few dozen records
 * filled once per process, which is a cheap way to make a class of bug
 * impossible.
 */
final class Shapes {

  /**
   * One signature, and which direction it goes.
   *
   * @param descriptor the C signature
   * @param critical whether the downcall skips the thread state transition
   * @param up whether the engine calls Java rather than the other way round
   */
  record Shape(FunctionDescriptor descriptor, boolean critical, boolean up) {}

  private static final Set<Shape> SEEN = ConcurrentHashMap.newKeySet();

  private Shapes() {}

  /** Remembers a call into the library. */
  static void down(FunctionDescriptor descriptor, boolean critical) {
    SEEN.add(new Shape(descriptor, critical, false));
  }

  /** Remembers a call out of it. */
  static void up(FunctionDescriptor descriptor) {
    SEEN.add(new Shape(descriptor, false, true));
  }

  /**
   * Everything bound so far.
   *
   * @return a snapshot, which is every shape this binding uses once the
   *     library has been loaded
   */
  static Set<Shape> seen() {
    return Set.copyOf(SEEN);
  }
}
