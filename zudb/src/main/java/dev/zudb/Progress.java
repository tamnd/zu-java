package dev.zudb;

/**
 * Called while a statement runs, to say how far it has got and to be asked
 * whether it should go on.
 *
 * <p>This is {@link Connection#rowsRead()} the other way round: a poll wants a
 * thread of its own to do the polling, and this one is called for you. It is
 * what a progress bar, a query timeout and a server that has to answer within
 * a deadline are all made of, and the deadline case is why the call has an
 * answer at all.
 *
 * <p>It runs on a thread of the library's, one per statement, never two at
 * once and never after the statement it belongs to has returned. Two things
 * follow from that. Whatever the callback touches has to be usable from
 * another thread, so a counter a progress bar reads should be an
 * {@code AtomicLong} rather than a field. And a callback must not call back
 * into the library on the connection it is reporting on, because that
 * connection is inside the executor and would answer
 * {@link ZuConcurrentException} at best.
 */
@FunctionalInterface
public interface Progress {

  /**
   * How far the running statement has got.
   *
   * @param rows how many rows it has read out of storage, which is rows read
   *     rather than rows answered because the statement a user is waiting on
   *     is exactly the one reading a hundred million rows to answer one
   * @param millis how long it has been running
   * @return true to let it go on, false to stop it, which ends it exactly as
   *     {@link Connection#interrupt()} would and raises
   *     {@link ZuInterruptedException} at the caller
   */
  boolean at(long rows, long millis);
}
