package dev.zudb;

/**
 * One diagnostic record, read off the C ABI and not yet decided about.
 *
 * <p>A record is a record whether it ends up thrown or handed back through
 * {@link Result#notices()}. The code, its standard text, the severity, the
 * place, the line and the documentation page are the same fields either way,
 * and the severity is what tells them apart. This is the one shape, and
 * {@link #toException()} is where it becomes the other.
 *
 * <p>Providers build these. Nothing else has any reason to.
 *
 * @param status what the call that produced this answered, {@link Status#OK}
 *     for a notice, since that is what the call returned
 * @param message zu's own account of the failure, naming the table, the token
 *     or the value, and complete on its own, so printing it alone is still a
 *     whole report
 * @param code the five-character GQLSTATUS code, or null for a condition the
 *     standard has no code for
 * @param condition the standard's words for the condition class and subclass,
 *     or null
 * @param severity how bad it is, never null
 * @param line the 1-based line the condition was raised at, or -1 for a
 *     failure with no position
 * @param column the 1-based column in characters, or -1
 * @param offset the 0-based byte index into the statement, or -1
 * @param excerpt the line the position is on without its newline, or null
 * @param subjectKind what the condition is about, when it is about something
 *     the statement named: one lower-case word out of graph, schema, label,
 *     property, variable, type and function, or null
 * @param subject the name itself, written the way the statement wrote it and
 *     with nothing around it, or null. Null exactly when subjectKind is
 * @param graph the graph the statement was running in, or null when the
 *     failure happened before there was one
 * @param schema the schema the statement was running in, or null
 * @param docUrl where this condition is written up, or null
 * @param retryable whether running the same statement again could succeed
 */
public record Diagnostic(
    Status status,
    String message,
    String code,
    String condition,
    Severity severity,
    int line,
    int column,
    int offset,
    String excerpt,
    String subjectKind,
    String subject,
    String graph,
    String schema,
    String docUrl,
    boolean retryable) {

  /**
   * The exception this record is, of the class its condition names.
   *
   * <p>The class comes from the two characters that open the GQLSTATUS code,
   * which is what a condition class is, and from the status when there is no
   * code. That is what lets a caller catch every one of the forty-two
   * conditions in class 22 by naming {@link ZuDataException} once.
   *
   * @return a new exception, never null
   */
  public ZuException toException() {
    String cls = code == null || code.length() < 2 ? "" : code.substring(0, 2);
    switch (cls) {
      case "08":
        return new ZuConnectionException(this);
      case "22":
        return new ZuDataException(this);
      case "25":
      case "2D":
      case "40":
        return new ZuTransactionException(this);
      case "42":
        return new ZuSyntaxException(this);
      default:
        break;
    }
    switch (status) {
      case MISUSE:
        return new ZuProgrammingException(this);
      case MISUSE_CONCURRENT:
        return new ZuConcurrentException(this);
      case MISUSE_CLOSED:
        return new ZuClosedException(this);
      case INTERRUPTED:
        return new ZuInterruptedException(this);
      case CONFLICT:
        return new ZuTransactionException(this);
      case IO:
      case CORRUPT:
        // A file that is not a database is the same mistake as a file that
        // is not there: a path that does not lead to a database. Almost
        // every one of these is a caller who mistyped a path or pointed at
        // the wrong file, and calling that an internal error tells them to
        // file a bug about somebody else's code.
        return new ZuConnectionException(this);
      default:
        return new ZuInternalException(this);
    }
  }

  /**
   * A record built out of what the C ABI answered.
   *
   * <p>This is how a provider makes one. It takes the status and the severity
   * as the numbers the library gave it, so that the mapping from those numbers
   * to the two enums happens here and once, rather than in every provider with
   * its own idea of what an unknown number means.
   *
   * @param status what {@code zu_error_status} answered
   * @param message what {@code zu_error_message} answered
   * @param code the GQLSTATUS code, or null
   * @param condition the standard's words for it, or null
   * @param severity what {@code zu_error_severity} answered
   * @param line the 1-based line, or -1
   * @param column the 1-based column, or -1
   * @param offset the 0-based byte index, or -1
   * @param excerpt the line the position is on, or null
   * @param subjectKind what {@code zu_error_subject_kind} answered, or null
   * @param subject what {@code zu_error_subject} answered, or null
   * @param graph what {@code zu_error_graph} answered, or null
   * @param schema what {@code zu_error_schema} answered, or null
   * @param docUrl where this condition is written up, or null
   * @param retryable whether running the same statement again could succeed
   * @return the record
   */
  public static Diagnostic of(
      int status,
      String message,
      String code,
      String condition,
      int severity,
      int line,
      int column,
      int offset,
      String excerpt,
      String subjectKind,
      String subject,
      String graph,
      String schema,
      String docUrl,
      boolean retryable) {
    return new Diagnostic(
        Status.of(status),
        message,
        code,
        condition,
        Severity.of(severity),
        line,
        column,
        offset,
        excerpt,
        subjectKind,
        subject,
        graph,
        schema,
        docUrl,
        retryable);
  }

  /**
   * A record for a failure that never reached the engine, which is every
   * mistake a caller makes in Java: a handle used after it closed, a column
   * index off the end, a parameter of a type zu has no place for.
   *
   * @param status what to call it, which is one of the misuse statuses
   * @param message what the caller did
   * @return a record with no code and no position, because a call that was
   *     never made raised no condition
   */
  public static Diagnostic misuse(Status status, String message) {
    return new Diagnostic(
        status, message, null, null, Severity.EXCEPTION, -1, -1, -1, null, null, null, null, null,
        null, false);
  }
}
