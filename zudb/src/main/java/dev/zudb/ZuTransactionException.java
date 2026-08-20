package dev.zudb;

/**
 * Classes 25, 2D and 40, and a write that lost a race: the transaction rather
 * than the statement is what went wrong.
 *
 * <p>Check {@link ZuException#retryable()} before running it again. A write
 * that lost to a concurrent one can be retried, because nothing of it was
 * applied. A statement whose completion is unknown cannot, because a retry
 * could do the work twice.
 */
public class ZuTransactionException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuTransactionException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
