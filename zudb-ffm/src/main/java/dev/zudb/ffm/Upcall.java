package dev.zudb.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A pointer to Java code the engine can call, which is the direction
 * everything else in this binding does not go.
 *
 * <p>An upcall stub is executable memory with a lifetime of its own, and that
 * lifetime is the awkward part. A stub has to outlive whatever holds it,
 * because the callback is the last thing to happen and may happen after the
 * thing that arranged it has gone. It cannot free itself either: closing the
 * arena a stub lives in from inside a call through that same stub is closing
 * the ground you are standing on.
 *
 * <p>So a spent arena goes on a queue and the next stub to be made closes it.
 * That costs nothing, needs no thread of ours, and bounds the outstanding
 * stubs at the number of callbacks that have not been spent yet.
 */
final class Upcall {

  /** Arenas nothing will call again, waiting for somebody else to close them. */
  private static final Queue<Arena> SPENT = new ConcurrentLinkedQueue<>();

  private final Arena arena;
  private final MemorySegment stub;

  private Upcall(Arena arena, MemorySegment stub) {
    this.arena = arena;
    this.stub = stub;
  }

  /**
   * Binds a method handle as a function pointer.
   *
   * @param target what to call, already bound to whatever it is called on
   * @param descriptor the C signature
   * @return the stub, which is freed by way of {@link #spend()} and the queue
   */
  @SuppressWarnings("restricted")
  static Upcall of(MethodHandle target, FunctionDescriptor descriptor) {
    sweep();
    // Shared rather than confined: the callback arrives on a thread of the
    // library's, and a confined arena would refuse the call it is there to
    // serve.
    Arena arena = Arena.ofShared();
    try {
      return new Upcall(arena, Linker.nativeLinker().upcallStub(target, descriptor, arena));
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

  /** Says nothing will call this again, so that the memory can go back. */
  void spend() {
    SPENT.add(arena);
  }

  /** Closes the arenas of every stub already spent. */
  private static void sweep() {
    for (Arena arena = SPENT.poll(); arena != null; arena = SPENT.poll()) {
      try {
        arena.close();
      } catch (RuntimeException e) {
        // A shared arena refuses to close while a thread is still inside a
        // call into it. Put it back and let the next one try.
        SPENT.add(arena);
        return;
      }
    }
  }
}
