package dev.zudb;

/**
 * What a cell holds, without reading it.
 *
 * <p>For the program that has to branch before it knows which accessor to
 * call. A program that is going to read the value anyway asks
 * {@link Row#get(int)} for a {@link Value} and switches over that, which says
 * the same thing and hands over the contents with it.
 */
public enum Type {
  /** No value. */
  NULL(0),
  /** A boolean. */
  BOOL(1),
  /** A 64-bit signed integer. */
  INT(2),
  /** A double. */
  FLOAT(3),
  /** A string. */
  STR(4),
  /** A node, which is a table and a row of it. */
  NODE(5),
  /** A relationship. */
  REL(6),
  /** A list, which recurses. */
  LIST(7),
  /** A path. */
  PATH(8),
  /** A date, a time, a datetime or a duration. */
  TEMPORAL(9),
  /** A record, whose fields are in name order. */
  RECORD(10),
  /** A graph, one of the two reference values, which has no contents to read. */
  GRAPH(11),
  /** A binding table, the other reference value. */
  BINDING_TABLE(12);

  private final int value;

  Type(int value) {
    this.value = value;
  }

  /**
   * The number this type is in the C ABI.
   *
   * @return the {@code ZU_TYPE_} value
   */
  public int value() {
    return value;
  }

  /**
   * The type a {@code ZU_TYPE_} value names.
   *
   * @param value what the C ABI returned
   * @return the type
   * @throws ZuProgrammingException if it is not one of them, which is what a
   *     client older than the library it loaded sees, and is worth saying
   *     plainly rather than reading as some other type
   */
  public static Type of(int value) {
    for (Type t : values()) {
      if (t.value == value) {
        return t;
      }
    }
    throw new ZuProgrammingException(
        Diagnostic.misuse(
            Status.MISUSE,
            "this libzu answered "
                + value
                + " for the type of a cell, and this client knows no such type: "
                + "it was written against ABI "
                + Zu.ABI_VERSION
                + " and the library is newer"));
  }
}
