package dev.zudb;

/**
 * The caller stopped the statement while it was running, through
 * {@link Connection#interrupt()} or by returning false from a progress
 * callback.
 *
 * <p>Nothing failed. The connection keeps its plans and its warm caches and
 * runs the next statement normally, which is the difference between this and
 * closing it. {@link ZuException#retryable()} is false, because a statement
 * the caller stopped on purpose is not one to run again on its behalf.
 *
 * <p>The name is spelled out rather than shortened, because
 * {@code InterruptedException} is a class in {@code java.lang} that means
 * something else and an import of the wrong one would compile.
 */
public class ZuInterruptedException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuInterruptedException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
