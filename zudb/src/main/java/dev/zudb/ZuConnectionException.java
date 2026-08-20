package dev.zudb;

/**
 * Class 08, and the failures the operating system reported: the database
 * could not be reached, could not be opened, or could not be read.
 *
 * <p>Nothing here is about the statement. A path that is not a zu database, a
 * file the process may not open, a disk that answered an error: the text was
 * never the problem and rewriting it will not help.
 */
public class ZuConnectionException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuConnectionException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
