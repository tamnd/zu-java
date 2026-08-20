package dev.zudb;

/**
 * A handle was used after the thing it belongs to closed.
 *
 * <p>Nothing was done, and nothing is wrong with the database. Statements
 * belong to the connection they were prepared on, so closing a connection
 * ends every statement of it; a result does not, because a result owns its
 * rows outright and stays readable after its connection has gone back to a
 * pool.
 */
public class ZuClosedException extends ZuProgrammingException {

  private static final long serialVersionUID = 1L;

  ZuClosedException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
