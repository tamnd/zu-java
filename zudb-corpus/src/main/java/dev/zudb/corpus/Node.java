package dev.zudb.corpus;

import java.util.List;

/**
 * One node of a corpus document, with the line it started on.
 *
 * <p>The accessors answer null for a node of the wrong shape rather than
 * throwing, because every caller is a reader deciding what a case says
 * and wanting to refuse it with a line number of its own. A reader that
 * had to catch something to find out that {@code rows:} held a mapping
 * would be writing the refusal twice.
 */
public final class Node {

  /** Which of the four shapes a node is. */
  public enum Kind {
    /** A single value, quoted or plain. */
    SCALAR,
    /** A list of nodes. */
    SEQ,
    /** Keys and what is under them, in the order they were written. */
    MAP,
    /**
     * A key with nothing under it.
     *
     * <p>This is a node rather than a refusal because a case that expects
     * no rows back writes {@code rows:} and stops, and that is a real
     * expectation which needs a spelling. Every accessor says no to it, so
     * a {@code name:} left blank is still caught by whoever wanted a name.
     */
    EMPTY
  }

  /**
   * One entry of a mapping.
   *
   * <p>Kept in the order it was written rather than in a hash map, because
   * the order is what a report cites and what a parameter list means.
   *
   * @param key the name left of the colon
   * @param value what is right of it, or what is indented under it
   */
  public record Pair(String key, Node value) {}

  private final Kind kind;
  private final int line;
  private final String text;
  private final boolean quoted;
  private final List<Node> items;
  private final List<Pair> pairs;

  private Node(Kind kind, int line, String text, boolean quoted, List<Node> items,
      List<Pair> pairs) {
    this.kind = kind;
    this.line = line;
    this.text = text;
    this.quoted = quoted;
    this.items = items;
    this.pairs = pairs;
  }

  static Node scalar(int line, String text, boolean quoted) {
    return new Node(Kind.SCALAR, line, text, quoted, null, null);
  }

  static Node seq(int line, List<Node> items) {
    return new Node(Kind.SEQ, line, null, false, List.copyOf(items), null);
  }

  static Node map(int line, List<Pair> pairs) {
    return new Node(Kind.MAP, line, null, false, null, List.copyOf(pairs));
  }

  static Node empty(int line) {
    return new Node(Kind.EMPTY, line, null, false, null, null);
  }

  /**
   * Which of the four shapes this node is.
   *
   * @return the kind, never null
   */
  public Kind kind() {
    return kind;
  }

  /**
   * The line the node started on, which is what a refusal cites.
   *
   * @return the line, counting from one
   */
  public int line() {
    return line;
  }

  /**
   * What kind of node this is in words, for a refusal that has to say what
   * it found instead of what it wanted.
   *
   * @return one of "a scalar", "a sequence", "a mapping" and "nothing"
   */
  public String what() {
    switch (kind) {
      case SCALAR:
        return "a scalar";
      case SEQ:
        return "a sequence";
      case MAP:
        return "a mapping";
      default:
        return "nothing";
    }
  }

  /**
   * The text of a scalar.
   *
   * @return the text, or null when this is not a scalar
   */
  public String text() {
    return kind == Kind.SCALAR ? text : null;
  }

  /**
   * Whether a scalar was written in quotes, which the value encoding turns
   * on: an INT64 written bare is a number some reader in some language will
   * round, and refusing it is the whole point of the encoding.
   *
   * @return whether it was quoted, and false for anything that is not a
   *     scalar
   */
  public boolean quoted() {
    return kind == Kind.SCALAR && quoted;
  }

  /**
   * The items of a sequence.
   *
   * @return the items, or null when this is not a sequence
   */
  public List<Node> seq() {
    return kind == Kind.SEQ ? items : null;
  }

  /**
   * The items of a sequence, counting a key with nothing under it as the
   * empty one.
   *
   * <p>Only a caller for whom empty is a meaningful answer should reach for
   * this. The rest want {@link #seq()}, so that a list somebody left
   * unfinished is refused rather than read as none.
   *
   * @return the items, empty for a key with nothing under it, or null when
   *     this is neither
   */
  public List<Node> seqOrEmpty() {
    return kind == Kind.EMPTY ? List.of() : seq();
  }

  /**
   * The entries of a mapping, in the order they were written.
   *
   * @return the entries, or null when this is not a mapping
   */
  public List<Pair> map() {
    return kind == Kind.MAP ? pairs : null;
  }

  /**
   * The value under one key.
   *
   * @param key the name to look for
   * @return the value, or null when this is not a mapping or the key is not
   *     in it
   */
  public Node get(String key) {
    if (kind != Kind.MAP) {
      return null;
    }
    for (Pair p : pairs) {
      if (p.key().equals(key)) {
        return p.value();
      }
    }
    return null;
  }

  /**
   * The keys that are not among the ones named, so a caller can refuse a
   * typo rather than drop the field on the floor.
   *
   * @param known the keys this caller reads
   * @return the keys it does not, in the order they were written, and empty
   *     when this is not a mapping
   */
  public List<String> unknown(String... known) {
    if (kind != Kind.MAP) {
      return List.of();
    }
    List<String> out = new java.util.ArrayList<>();
    for (Pair p : pairs) {
      boolean found = false;
      for (String k : known) {
        found = found || k.equals(p.key());
      }
      if (!found) {
        out.add(p.key());
      }
    }
    return out;
  }
}
