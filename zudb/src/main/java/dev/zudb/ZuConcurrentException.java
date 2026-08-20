package dev.zudb;

/**
 * Two threads used one connection at once.
 *
 * <p>A connection is exactly the state that cannot be shared: a file handle,
 * the caches, and the plans compiled against a catalog. The second thread is
 * refused rather than raced, and nothing was done. A program that queries
 * from four threads opens one database and connects four times, which is
 * {@link Database#connect()} or {@link Connection#duplicate()}.
 *
 * <p>{@link Connection#interrupt()} and {@link Connection#rowsRead()} are the
 * exception and the point of it: both are meant to be called from another
 * thread while a statement runs, and neither raises this.
 */
public class ZuConcurrentException extends ZuProgrammingException {

  private static final long serialVersionUID = 1L;

  ZuConcurrentException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
