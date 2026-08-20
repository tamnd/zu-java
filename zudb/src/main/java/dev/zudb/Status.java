package dev.zudb;

/**
 * What a call into libzu answered, which is a different question from which
 * condition it raised.
 *
 * <p>The GQLSTATUS code a user reads is on the failure, not here, which is
 * what keeps this from growing a value per condition. What this says is the
 * shape of the answer: whether the caller broke a contract, whether the
 * engine refused the work, or whether the work simply stopped.
 */
public enum Status {
  /** The call did what it was asked. */
  OK(0),
  /** Well formed, and there is nothing to read: a column of a result with no rows. */
  DONE(2),
  /** The engine refused the work, and the failure says why. */
  ERROR(3),
  /**
   * The caller broke the contract: a closed handle, an index out of range, an
   * accessor asked for a column that does not hold what it reads. Nothing was
   * done, and nothing is wrong with the database.
   */
  MISUSE(4),
  /** Two threads used one connection at once. Nothing was done. Connect again rather than share. */
  MISUSE_CONCURRENT(5),
  /** A statement was used after its connection closed. Nothing was done. */
  MISUSE_CLOSED(6),
  /**
   * The caller stopped the statement while it was running. Nothing is wrong
   * with the connection and the next statement on it runs normally.
   */
  INTERRUPTED(7),
  /** A write lost to a concurrent one. */
  CONFLICT(8),
  /** The file says something that cannot be true. */
  CORRUPT(9),
  /** Not implemented in this build, as against declined. */
  UNSUPPORTED(10),
  /** The operating system refused a read or a write. */
  IO(11),
  /**
   * A value this release of the client has no name for, which is what a
   * client older than the library it loaded sees. Nothing succeeded, since
   * success is the one value that will never move.
   */
  UNKNOWN(-1);

  private final int value;

  Status(int value) {
    this.value = value;
  }

  /**
   * The number this status is in the C ABI.
   *
   * @return the {@code zu_status} value, and -1 for {@link #UNKNOWN}
   */
  public int value() {
    return value;
  }

  /**
   * The status a {@code zu_status} value names.
   *
   * @param value what the C ABI returned
   * @return the status, and {@link #UNKNOWN} for a value this release has no
   *     name for
   */
  public static Status of(int value) {
    for (Status s : values()) {
      if (s.value == value && s != UNKNOWN) {
        return s;
      }
    }
    return UNKNOWN;
  }
}
