package dev.zudb;

/**
 * Class 42: the statement could not be parsed, or it named something that is
 * not there.
 *
 * <p>This is the failure that always carries a position, which is what
 * {@link ZuException#caret()} is for.
 */
public class ZuSyntaxException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuSyntaxException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
