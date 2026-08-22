package dev.zudb;

/**
 * Class 08, and the failures the operating system reported: the database
 * could not be reached, could not be opened, or could not be read.
 *
 * <p>Nothing here is about the statement. A path that is not a zu database, a
 * file the process may not open, a disk that answered an error: the text was
 * never the problem and rewriting it will not help.
 *
 * <p>A file whose contents are not a database is here rather than in
 * {@link ZuInternalException}, and the two readings of that were weighed. A
 * header that says something impossible could be a database this process
 * corrupted, and it could be a JPEG somebody pointed at. The second is what
 * almost every one of these is, so this is the class that sends a caller to
 * look at the path they passed rather than to open a bug.
 */
public class ZuConnectionException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuConnectionException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
