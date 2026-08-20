package dev.zudb.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * What a frame's release callback is on this side.
 *
 * <p>It is how a host learns the engine has finished with the buffers it lent:
 * it runs once, on a thread of the library's, after the last statement reading
 * the frame ends, which is neither the unregister that preceded it nor the
 * free.
 */
final class Release {

  private static final Logger LOG = System.getLogger("dev.zudb");

  private static final FunctionDescriptor DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS);

  private static final MethodHandle RUN = run();

  private final Runnable body;
  private volatile Upcall upcall;

  private Release(Runnable body) {
    this.body = body;
  }

  /**
   * A {@code void (*)(void *)} the engine can call, bound to this runnable.
   *
   * @param body what to run when the engine is finished with the frame
   * @return the release
   */
  static Release of(Runnable body) {
    Release release = new Release(body);
    release.upcall = Upcall.of(RUN.bindTo(release), DESCRIPTOR);
    return release;
  }

  /**
   * The function pointer.
   *
   * @return the stub
   */
  MemorySegment stub() {
    return upcall.stub();
  }

  /**
   * Says the callback will never be called, for the frame that failed to be
   * made at all, so that the stub goes the way a spent one goes.
   */
  void abandon() {
    upcall.spend();
  }

  /**
   * Called by the engine.
   *
   * <p>Nothing may be thrown out of here. An exception crossing an upcall
   * takes the whole JVM down, and a host's release callback is exactly the
   * kind of code that throws: it takes a lock, or a runtime's interpreter
   * lock, and lets go of buffers. So it is logged and swallowed, which leaves
   * the process alive and the mistake findable.
   *
   * @param owner the pointer passed at creation, which this binding does not
   *     use because the runnable already knows what it owns
   */
  @SuppressWarnings("unused")
  private void run(MemorySegment owner) {
    try {
      body.run();
    } catch (Throwable t) {
      LOG.log(Level.ERROR, "a zu frame release callback threw, which nothing above it can see", t);
    } finally {
      upcall.spend();
    }
  }

  private static MethodHandle run() {
    try {
      return MethodHandles.lookup()
          .findVirtual(Release.class, "run", MethodType.methodType(void.class, MemorySegment.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
