package dev.zudb.corpus;

/** How this package writes a value into a message. */
final class Text {

  private Text() {}

  /**
   * A string the way Rust's {@code {:?}} writes one.
   *
   * <p>Every refusal in the corpus is written in several languages and
   * diffed across them, so a value quoted one way here and another way
   * there would be a difference in the report that is not a difference in
   * the answer. Java's own escaping and Rust's disagree about what is
   * unprintable, so the quoting is written out rather than borrowed.
   *
   * @param text the string to write
   * @return the string in quotes, with the four escapes Rust uses
   */
  static String quote(String text) {
    StringBuilder out = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
        case '\\':
          out.append('\\').append(c);
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          out.append(c);
          break;
      }
    }
    return out.append('"').toString();
  }

  /**
   * A refusal, formatted.
   *
   * @param format the message, as {@link String#format} spells one
   * @param args what goes in it
   * @return the exception, for a caller to throw
   */
  static CorpusException refuse(String format, Object... args) {
    return new CorpusException(String.format(format, args));
  }
}
