package dev.zudb;

import java.util.Optional;

/**
 * What every zu failure is, and the class to catch to catch them all.
 *
 * <p>Every failure the engine reports is a GQLSTATUS condition: a
 * five-character code from ISO/IEC 39075, a severity, and often the place in
 * the statement that raised it. All of that arrives here as fields, so a
 * caller reads {@link #code()} and never a regular expression over
 * {@link #getMessage()}.
 *
 * <p>There is one subclass per condition class, which is what the two
 * characters that open a code are for. Catching {@link ZuDataException}
 * catches every one of the forty-two conditions in class 22 without listing
 * them, and a condition zu adds to that class later is caught by the same
 * {@code catch}.
 *
 * <p>The fields are empty when the condition has no answer for them, rather
 * than filled with a guess. A division by zero happens while the statement
 * runs and has no token to point at, so it carries a code and no position; a
 * statement that failed to parse carries both.
 *
 * <p>These are unchecked, and that is a decision rather than an oversight.
 * There is nothing a caller can do about a syntax error at the call site; the
 * one failure worth handling is a conflict, and a retry goes around a block
 * rather than around a statement; and a checked exception would put a
 * {@code throws} on every method of every program that reads a row. What a
 * caller does want is {@link #retryable()}, which is a field and not a class.
 */
public class ZuException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Diagnostic diagnostic;

  ZuException(Diagnostic diagnostic) {
    super(diagnostic.message());
    this.diagnostic = diagnostic;
  }

  /**
   * The whole record this failure was built from, for a caller that would
   * rather pass one value around than fifteen.
   *
   * @return the record, never null
   */
  public Diagnostic diagnostic() {
    return diagnostic;
  }

  /**
   * What the call that failed answered, which is the shape of the failure as
   * against the condition it raised.
   *
   * @return the status, never null
   */
  public Status status() {
    return diagnostic.status();
  }

  /**
   * The five-character GQLSTATUS code, {@code "42001"} for a syntax error.
   * Empty for the few failures the standard has no condition for, such as a
   * statement that was interrupted.
   *
   * @return the code, if there is one
   */
  public Optional<String> code() {
    return Optional.ofNullable(diagnostic.code());
  }

  /**
   * The standard's own words for the condition, never paraphrased, for
   * example {@code "syntax error or access rule violation, invalid syntax"}.
   * This is what a conformance harness grades.
   *
   * @return the condition text, if there is one
   */
  public Optional<String> condition() {
    return Optional.ofNullable(diagnostic.condition());
  }

  /**
   * How bad it is.
   *
   * @return the severity, never null
   */
  public Severity severity() {
    return diagnostic.severity();
  }

  /**
   * Where in the statement it happened, both counted from one, the column in
   * characters so a line of multi-byte text does not read as wider than it
   * looks. Empty when the condition happened somewhere the text cannot name.
   *
   * @return the position, if there is one
   */
  public Optional<Position> position() {
    return diagnostic.line() < 0
        ? Optional.empty()
        : Optional.of(new Position(diagnostic.line(), diagnostic.column(), diagnostic.offset()));
  }

  /**
   * The whole line the position is on, quoted out of the statement, for the
   * caller who has the failure and no longer has the text. Empty when there
   * is no position, when the line is empty, and when the line is longer than
   * anyone would read under a caret, since a line cut to fit would put the
   * column somewhere it is not.
   *
   * @return the excerpt, if there is one
   */
  public Optional<String> excerpt() {
    return Optional.ofNullable(diagnostic.excerpt());
  }

  /**
   * What kind of thing the condition is about, when it is about something the
   * statement named: one lower-case word out of graph, schema, label,
   * property, variable, type and function.
   *
   * <p>Kept apart from {@link #subject()} rather than glued to the front of
   * it, so that asking whether a failure is about a label is one string
   * compared against one word. This and {@link #subject()} are both present
   * or both empty.
   *
   * @return the kind, if the condition is about something named
   */
  public Optional<String> subjectKind() {
    return Optional.ofNullable(diagnostic.subjectKind());
  }

  /**
   * The name the condition is about, written the way the statement wrote it
   * and with nothing around it, which is what an editor underlines.
   *
   * @return the name, if the condition is about something named
   */
  public Optional<String> subject() {
    return Optional.ofNullable(diagnostic.subject());
  }

  /**
   * The graph the statement was running in, which ISO 39075 subclause 23.2
   * asks a diagnostic record to name.
   *
   * @return the graph, empty when the failure happened before there was one
   */
  public Optional<String> graph() {
    return Optional.ofNullable(diagnostic.graph());
  }

  /**
   * The schema the statement was running in.
   *
   * @return the schema, empty when the failure happened before there was one
   */
  public Optional<String> schema() {
    return Optional.ofNullable(diagnostic.schema());
  }

  /**
   * The page that documents this condition, so a program hands a reader a
   * page rather than five characters to search for.
   *
   * @return the URL, if there is one
   */
  public Optional<String> docUrl() {
    return Optional.ofNullable(diagnostic.docUrl());
  }

  /**
   * Whether running the same statement again could succeed. True for a write
   * that lost to a concurrent one, since nothing of it was applied. False for
   * text that will not parse, and false for a statement the caller
   * interrupted, which did not fail so much as stop.
   *
   * <p>A retry loop reads this rather than carrying a list of codes, which is
   * the sort of list that is right in one binding and stale in the other
   * five.
   *
   * @return whether a retry is worth it
   */
  public boolean retryable() {
    return diagnostic.retryable();
  }

  /**
   * The excerpt with a caret under the column, ready to print. Empty when
   * there is no excerpt to point at.
   *
   * <p>This is the one piece of formatting the library does, because every
   * caller that prints a failure writes it otherwise and half of them count
   * the column wrong.
   *
   * @return the two lines, if there is an excerpt and a column
   */
  public Optional<String> caret() {
    String excerpt = diagnostic.excerpt();
    int column = diagnostic.column();
    if (excerpt == null || column < 1) {
      return Optional.empty();
    }
    return Optional.of(excerpt + System.lineSeparator() + " ".repeat(column - 1) + "^");
  }

  /**
   * Where in a statement a condition was raised.
   *
   * @param line the line, counting from one
   * @param column the column on that line in characters, counting from one, and a
   *     valid index into {@link ZuException#excerpt()} after subtracting that one
   * @param offset bytes into the statement, counting from zero, for a caller that
   *     slices the text rather than printing it, always on a character boundary
   */
  public record Position(int line, int column, int offset) {}
}
