package dev.zudb;

/**
 * A failure the engine could not describe as a condition: a corrupt file, an
 * assumption that did not hold, a call this build does not implement.
 *
 * <p>Worth reporting at <a href="https://github.com/tamnd/zu/issues">the
 * engine's issue tracker</a> with the statement that produced it.
 */
public class ZuInternalException extends ZuException {

  private static final long serialVersionUID = 1L;

  ZuInternalException(Diagnostic diagnostic) {
    super(diagnostic);
  }
}
