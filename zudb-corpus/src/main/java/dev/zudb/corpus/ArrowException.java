package dev.zudb.corpus;

/**
 * The export saying no, with what it said.
 *
 * <p>A type of its own, so that a refusal on the way out is told apart from
 * a schema that came back different from the one the case wants. A case
 * writing {@code arrow: refused} turns on exactly that difference: Arrow has
 * a time and a timestamp and nothing in between, so a time with an offset
 * has nowhere to go, and a client that quietly moved it to UTC would be
 * moving the value.
 */
public class ArrowException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The export saying no.
   *
   * @param message what it said, which is the whole of it and carries no
   *     prefix, for the reason {@link CorpusException} carries none
   */
  public ArrowException(String message) {
    super(message);
  }
}
