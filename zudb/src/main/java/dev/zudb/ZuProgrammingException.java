package dev.zudb;

/**
 * The caller broke the contract, in Java or in the C ABI underneath it: a
 * handle used after it closed, a column index off the end, an accessor asked
 * for a column that does not hold what it reads, a parameter of a type zu has
 * no place for.
 *
 * <p>Nothing reached the engine, so nothing happened to the database. This is
 * a bug in the program rather than a condition of the data, and it is the one
 * class here that a passing test suite should never see.
 */
public class ZuProgrammingException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuProgrammingException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
