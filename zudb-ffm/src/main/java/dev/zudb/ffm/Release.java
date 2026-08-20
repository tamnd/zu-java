package dev.zudb.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The one place this binding hands the engine a pointer to Java code rather
 * than the other way round.
 *
 * <p>A frame's release callback is how a host learns the engine has finished
 * with the buffers it lent: it runs once, on a thread of the library's, after
 * the last statement reading the frame ends. Reaching Java from there needs an
 * upcall stub, which is executable memory with a lifetime of its own.
 *
 * <p>That lifetime is the awkward part. The stub has to outlive the frame,
 * because the callback is the last thing to happen and may happen after the
 * frame was freed, so it cannot hang off the frame. It cannot free itself
 * either: closing the arena a stub lives in from inside a call through that
 * same stub is closing the ground you are standing on. So a spent arena goes
 * on a queue and the next stub to be made closes it, which costs nothing, needs
 * no thread of ours, and bounds the outstanding stubs at the number of frames
 * whose callbacks have not run yet.
 */
final class Release {

  private static final Logger LOG = System.getLogger("dev.zudb");

  private static final FunctionDescriptor DESCRIPTOR = FunctionDescriptor.ofVoid(ADDRESS);

  private static final MethodHandle RUN = run();

  /** Arenas whose callback has been and gone, waiting for somebody else to close them. */
  private static final Queue<Arena> SPENT = new ConcurrentLinkedQueue<>();

  private final Runnable body;
  private final Arena arena;

  private Release(Runnable body, Arena arena) {
    this.body = body;
    this.arena = arena;
  }

  private MemorySegment stub;

  /**
   * A {@code void (*)(void *)} the engine can call, bound to this runnable.
   *
   * @param body what to run when the engine is finished with the frame
   * @return the release, which frees itself by way of the queue above
   */
  @SuppressWarnings("restricted")
  static Release of(Runnable body) {
    sweep();
    // Shared rather than confined: the callback arrives on a thread of the
    // library's, and a confined arena would refuse the call it is there to
    // serve.
    Arena arena = Arena.ofShared();
    try {
      Release release = new Release(body, arena);
      release.stub = Linker.nativeLinker().upcallStub(RUN.bindTo(release), DESCRIPTOR, arena);
      return release;
    } catch (RuntimeException | Error e) {
      arena.close();
      throw e;
    }
  }

  /**
   * The function pointer.
   *
   * @return the stub
   */
  MemorySegment stub() {
    return stub;
  }

  /**
   * Says the callback will never be called, for the frame that failed to be
   * made at all, so that the stub goes the way a spent one goes.
   */
  void abandon() {
    SPENT.add(arena);
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
      SPENT.add(arena);
    }
  }

  /** Closes the arenas of every callback that has already run. */
  private static void sweep() {
    for (Arena arena = SPENT.poll(); arena != null; arena = SPENT.poll()) {
      try {
        arena.close();
      } catch (RuntimeException e) {
        // A shared arena refuses to close while a thread is still inside a
        // call into it. Put it back and let the next frame try.
        SPENT.add(arena);
        return;
      }
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
