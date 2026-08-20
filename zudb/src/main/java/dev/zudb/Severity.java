package dev.zudb;

/**
 * How bad a diagnostic record is, which is what decides whether a binding
 * raises at all.
 *
 * <p>An exception replaces a result and arrives as a throw. A warning rides
 * along with one and arrives through {@link Result#notices()}, because a
 * statement that dropped a null out of an aggregate still has rows to give
 * you and the standard still wants you told.
 */
public enum Severity {
  /** The statement did what it was asked. */
  SUCCESS,
  /** Successful completion, with the result omitted. */
  NO_DATA,
  /** The statement answered, and raised a condition on the way. */
  WARNING,
  /** Something worth telling the caller that is neither of the above. */
  INFORMATIONAL,
  /** The statement was refused or could not finish. */
  EXCEPTION;

  /**
   * The severity a {@code zu_error_severity} value names.
   *
   * @param value what the C ABI returned
   * @return the severity, and {@link #EXCEPTION} for a value this release has
   *     no name for, since treating an unknown severity as harmless is the
   *     one reading that loses data
   */
  static Severity of(int value) {
    switch (value) {
      case 0:
        return SUCCESS;
      case 1:
        return NO_DATA;
      case 2:
        return WARNING;
      case 3:
        return INFORMATIONAL;
      default:
        return EXCEPTION;
    }
  }
}
