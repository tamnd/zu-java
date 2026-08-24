package dev.zudb.corpus;

/**
 * A corpus this reader will not read, with the line it gave up on.
 *
 * <p>It is a type of its own rather than a plain failure so that the
 * command can tell a corpus it cannot read from a case that did not pass.
 * Those are two different exits: the first is a broken file and the
 * second is a client that disagrees with the engine.
 *
 * <p>The message is the whole of the refusal. It opens with the line it
 * happened on unless the file has no line to blame, and it carries no
 * stack, because a reader that printed the shape of this package at
 * somebody trying to fix a case would be answering a question they did
 * not ask.
 */
public class CorpusException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * A refusal with the message it carries.
   *
   * @param message the whole of the refusal
   */
  public CorpusException(String message) {
    super(message);
  }
}
