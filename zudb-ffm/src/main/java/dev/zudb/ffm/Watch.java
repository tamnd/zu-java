package dev.zudb.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.zudb.Progress;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** What a progress callback is on this side. */
final class Watch {

  private static final Logger LOG = System.getLogger("dev.zudb");

  private static final FunctionDescriptor DESCRIPTOR =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG);

  private static final MethodHandle AT = at();

  private final Progress body;
  private volatile Upcall upcall;

  private Watch(Progress body) {
    this.body = body;
  }

  /**
   * A {@code zu_progress_fn} bound to this watcher.
   *
   * @param body what to call
   * @return the watch
   */
  static Watch of(Progress body) {
    Watch watch = new Watch(body);
    watch.upcall = Upcall.of(AT.bindTo(watch), DESCRIPTOR);
    return watch;
  }

  /**
   * The function pointer.
   *
   * @return the stub
   */
  MemorySegment stub() {
    return upcall.stub();
  }

  /** Says nothing will call this again, which is what taking the arrangement back means. */
  void spend() {
    upcall.spend();
  }

  /**
   * Called by the engine.
   *
   * <p>Nothing may be thrown out of an upcall, so a watcher that throws is
   * logged and answered as though it had asked for the statement to stop. That
   * is the reading that loses least: a progress callback that threw is a
   * program that has stopped wanting the answer, and letting the statement run
   * on would only mean throwing the answer away later.
   *
   * @param userData the pointer passed at creation, which this binding does
   *     not use because the watcher is already bound to this stub
   * @param rows how many rows have been read
   * @param millis how long the statement has been running
   * @return 1 to let it go on, 0 to stop it
   */
  @SuppressWarnings("unused")
  private int at(MemorySegment userData, long rows, long millis) {
    try {
      return body.at(rows, millis) ? 1 : 0;
    } catch (Throwable t) {
      LOG.log(Level.ERROR, "a zu progress callback threw, so the statement it watched is stopping", t);
      return 0;
    }
  }

  private static MethodHandle at() {
    try {
      return MethodHandles.lookup()
          .findVirtual(
              Watch.class,
              "at",
              MethodType.methodType(
                  int.class, MemorySegment.class, long.class, long.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
