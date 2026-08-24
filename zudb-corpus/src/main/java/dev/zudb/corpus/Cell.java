package dev.zudb.corpus;

import dev.zudb.Value;
import java.util.Arrays;
import java.util.Map;

/**
 * One value, in the shape the corpus compares values in.
 *
 * <p>Not {@link dev.zudb.Value}, for two reasons. It is sealed, so this
 * package could not add to it if it wanted to. And its node, rel and path
 * carry the id of the table the row is in, where a case writes the table's
 * name: an id is a number the file decided and every client builds its own
 * file, so a case naming one would be asserting something about the order
 * the tables went in. A node here is {@code person#1} and an edge is
 * {@code knows#0->1}, which is what the case wrote.
 *
 * <p>A rel also carries a fourth field the corpus does not write, which is
 * where the edge's properties sit, and that is its place in the order the
 * table was loaded in rather than anything a case chose. A pair may run
 * more than once, and a case that has to tell two parallel edges apart
 * asserts a property of them instead.
 *
 * <p>Sealed, so a switch over the twelve is exhaustive and a thirteenth
 * cannot be added without every reader of one being made to say what it
 * does about it. That is the JVM's answer to the Go runner's type switch
 * over {@code any}, and a stricter one: there, a shape nobody handled fell
 * through to a default.
 *
 * <p>The declared width is dropped on the way in, so a case that says
 * {@code INT8} and one that says {@code INT64} both come to an {@link Int}.
 * That is a fact about this engine rather than about the corpus, since its
 * own value is one signed 64 bit integer either way.
 *
 * <p>Two of these are the same value when they are {@link Object#equals(Object)
 * equal}, which is the whole reason for records here. A float is the case
 * that usually needs a comparison written by hand: NaN is not equal to
 * itself and a case asserting NaN has to pass, and 0.0 equals -0.0 and a
 * case asserting -0.0 has to fail on 0.0, because the sign of zero is
 * exactly the sort of thing that survives one binding and not another. A
 * record's generated equality compares a double with
 * {@code Double.compare}, which says yes to the first pair and no to the
 * second, so it is already the comparison the corpus wants. {@link Bytes}
 * is the one that is not, since an array compares by identity, and it is
 * written out below.
 */
public sealed interface Cell {

  /** The absence of a value, which is a value a case asserts. */
  record Null() implements Cell {}

  /** The one null, since they are all equal and there is no state to hold. */
  Cell NULL = new Null();

  /**
   * A boolean.
   *
   * @param value what it is
   */
  record Bool(boolean value) implements Cell {}

  /**
   * An integer, of whatever width the case declared.
   *
   * @param value what it is
   */
  record Int(long value) implements Cell {}

  /**
   * A float. A {@code FLOAT32} arrives here already rounded through the
   * narrower type, which is what the engine holds it as.
   *
   * @param value what it is
   */
  record Float(double value) implements Cell {}

  /**
   * A string.
   *
   * @param value what it is
   */
  record Str(String value) implements Cell {}

  /**
   * A byte string.
   *
   * @param value the octets, which are not copied on the way in or out
   *     because nothing in this package writes to one
   */
  record Bytes(byte[] value) implements Cell {

    @Override
    public boolean equals(Object other) {
      return other instanceof Bytes b && Arrays.equals(value, b.value);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
      return "Bytes[" + Values.hexits(value) + "]";
    }
  }

  /**
   * A date, a time, a datetime or a duration, in the client's own shape,
   * which is one count and the unit that count is in.
   *
   * @param value which of the seven, and the count
   */
  record Time(Value.Temporal value) implements Cell {}

  /**
   * A list.
   *
   * @param items what is in it, in order
   */
  record List(java.util.List<Cell> items) implements Cell {}

  /**
   * A node, as a case names it.
   *
   * @param table the node table's name
   * @param offset the row's number within that table, counted from zero in
   *     the order the load wrote it
   */
  record Node(String table, long offset) implements Cell {}

  /**
   * An edge, as a case names it.
   *
   * @param table the rel table's name
   * @param source the row the edge runs from
   * @param target the row it runs to
   */
  record Edge(String table, long source, long target) implements Cell {}

  /**
   * A path, which is the walk a case compares rather than the edges the
   * engine happened to hand back: two walks that cross the same pairs are
   * the same walk whichever copy of a parallel edge was taken.
   *
   * @param items a {@link Node} at each end and an {@link Edge} between
   *     every two of them
   */
  record Path(java.util.List<Cell> items) implements Cell {}

  /**
   * A record.
   *
   * @param fields the names and what is under them, compared by name and
   *     printed in the order a sort puts them in, so that a report of one
   *     reads the same twice
   */
  record Record(Map<String, Cell> fields) implements Cell {}

  /**
   * A value the corpus has no spelling for, which is a graph or a binding
   * table.
   *
   * <p>This is here so that a report carrying one says so rather than
   * dying, and it is deliberately not a shape a case can write: no
   * {@code type} names it, so it can only arrive from the engine, and it
   * can never be equal to anything a case asserts.
   *
   * @param value what came back
   */
  record Other(Value value) implements Cell {}

  /**
   * A value from the engine in the shape the corpus compares values in.
   *
   * <p>Everything a table holds is spelled the same on both sides and comes
   * through untouched. A graph value is not: the engine's node and edge
   * carry the id of their table where a case writes its name, so they are
   * put into the shapes above before anything is compared, which is what
   * the Rust runner's {@code from_engine} does for the same reason.
   *
   * <p>A table with no name is spelled {@code #7} after its id, which is
   * what a node column of an Arrow export is named when there is no catalog
   * to ask. It should not happen here, since the connection is right there,
   * and it is a spelling rather than a failure because a report saying "the
   * case wants person#1 and this is #7" is more use to whoever has to fix
   * it than one that died.
   *
   * @param value what the statement gave back
   * @param named what a table id is called, for the three shapes that carry
   *     one
   * @return the same value, ready to compare
   */
  static Cell of(Value value, java.util.function.IntFunction<String> named) {
    return switch (value) {
      case Value.Null _ -> NULL;
      case Value.Bool v -> new Bool(v.value());
      case Value.Int v -> new Int(v.value());
      case Value.Float v -> new Float(v.value());
      case Value.Str v -> new Str(v.value());
      case Value.Bytes v -> new Bytes(v.value());
      case Value.Temporal v -> new Time(v);
      case Value.Node v -> new Node(named.apply(v.table()), v.offset());
      case Value.Rel v -> new Edge(named.apply(v.table()), v.source(), v.target());
      case Value.List v -> new List(all(v.items(), named));
      case Value.Path v -> new Path(all(v.items(), named));
      case Value.Record v -> record(v, named);
      // A graph and a binding table, which are the two the corpus has no
      // spelling for and which no case can write.
      default -> new Other(value);
    };
  }

  private static java.util.List<Cell> all(java.util.List<Value> items,
      java.util.function.IntFunction<String> named) {
    java.util.List<Cell> out = new java.util.ArrayList<>(items.size());
    for (Value item : items) {
      out.add(of(item, named));
    }
    return java.util.List.copyOf(out);
  }

  private static Cell record(Value.Record value,
      java.util.function.IntFunction<String> named) {
    Map<String, Cell> out = new java.util.LinkedHashMap<>();
    for (Value.Field field : value.fields()) {
      out.put(field.name(), of(field.value(), named));
    }
    return new Record(Map.copyOf(out));
  }
}
