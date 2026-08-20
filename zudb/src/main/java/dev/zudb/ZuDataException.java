package dev.zudb;

/**
 * Class 22: a value was wrong. Division by zero, a cast that could not be
 * made, a number that did not fit, a string that is not the shape the
 * function wanted.
 *
 * <p>Most of these happen while the statement runs rather than while it is
 * parsed, so most of them carry a code and no position: by then there is no
 * token left to point at.
 */
public class ZuDataException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuDataException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
