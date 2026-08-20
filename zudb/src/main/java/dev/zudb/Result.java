package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Everything a statement answered.
 *
 * <p>A result owns its rows outright, so it stays readable after the
 * connection that produced it has gone back to a pool. What it does not
 * outlive is {@link #close()}, and that includes every buffer the columnar
 * readers handed back and every string that came out of a row.
 *
 * <p>There are four ways to read it, in the order you reach for them.
 * {@link #stream()} for a row at a time, which is what most code wants.
 * {@link #longs(int)} and the three beside it for a whole column, borrowed
 * from the engine rather than copied, which is what a million rows wants.
 * {@link #chunks()} for a whole column read a chunk at a time, which is what
 * a million rows wants when you are not going to read all of them. And
 * {@link #exportArrow(long, long)} for handing the whole thing to something
 * else entirely, which spends the result rather than reading it.
 *
 * <pre>{@code
 * try (Result r = conn.query("MATCH (p:Person) RETURN p.name AS name")) {
 *     r.stream().map(row -> row.getString("name")).forEach(System.out::println);
 * }
 * }</pre>
 */
public final class Result implements AutoCloseable, Iterable<Row> {

  /**
   * How many rows a consumer sees per Arrow batch when nobody names a number,
   * which is what {@link #exportArrow(long)} asks the engine for.
   */
  public static final long DEFAULT_BATCH = 65536;

  private final ZuBinding zu;
  private final AtomicLong handle;
  private final Connection conn;
  private final long rows;
  private final int columns;
  private final List<String> names;
  private final Map<String, Integer> byName;

  Result(ZuBinding zu, long handle, Connection conn) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
    this.conn = conn;
    this.rows = zu.resultRows(handle);
    this.columns = zu.resultCols(handle);
    List<String> found = new ArrayList<>(columns);
    Map<String, Integer> index = new HashMap<>(columns * 2);
    for (int c = 0; c < columns; c++) {
      String name = zu.resultColName(handle, c);
      found.add(name);
      // A statement may name two columns the same thing, and the first
      // is the one a name resolves to, which is what every other client
      // of this engine does and what an index makes unambiguous.
      index.putIfAbsent(name, c);
    }
    this.names = Collections.unmodifiableList(found);
    this.byName = Collections.unmodifiableMap(index);
  }

  /**
   * How many rows.
   *
   * @return the count, 0 for a statement that answered with none
   */
  public long rows() {
    return rows;
  }

  /**
   * How many columns.
   *
   * @return the count
   */
  public int columns() {
    return columns;
  }

  /**
   * What the columns are called, in order.
   *
   * @return the names, unmodifiable
   */
  public List<String> columnNames() {
    return names;
  }

  /**
   * What one column is called.
   *
   * @param column the column, counting from zero
   * @return the name
   */
  public String columnName(int column) {
    checkColumn(column);
    return names.get(column);
  }

  /**
   * Which column a name is.
   *
   * @param name what the statement called it
   * @return the column, counting from zero
   * @throws ZuProgrammingException if the result has no such column, naming
   *     the ones it does have, because the answer is almost always a typo or
   *     a missing {@code AS}
   */
  public int columnIndex(String name) {
    Integer c = byName.get(name);
    if (c == null) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "this result has no column called " + name + "; it has " + String.join(", ", names)));
    }
    return c;
  }

  /**
   * One row.
   *
   * @param index the row, counting from zero
   * @return the row, which is good until this result closes
   */
  public Row row(long index) {
    if (index < 0 || index >= rows) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE, "row " + index + " of a result with " + rows + " of them"));
    }
    return new Row(this, index);
  }

  /**
   * Every row, in order.
   *
   * <p>The stream is lazy and reads out of the result as it goes, so it has
   * to be consumed before {@link #close()}. Collecting it inside the
   * try-with-resources and using the list afterwards is the shape that always
   * works.
   *
   * @return the rows
   */
  public Stream<Row> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Override
  public Iterator<Row> iterator() {
    return new Iterator<>() {
      private long next;

      @Override
      public boolean hasNext() {
        return next < rows;
      }

      @Override
      public Row next() {
        if (next >= rows) {
          throw new NoSuchElementException();
        }
        return new Row(Result.this, next++);
      }
    };
  }

  @Override
  public Spliterator<Row> spliterator() {
    return Spliterators.spliterator(
        iterator(), rows, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE);
  }

  /**
   * What a cell holds, without reading it.
   *
   * @param row the row, counting from zero
   * @param column the column, counting from zero
   * @return the type
   */
  public Type cellType(long row, int column) {
    return Type.of(zu.resultCellType(open(), row, column));
  }

  /**
   * The completion condition of the statement: {@code "00000"} for one that
   * answered with columns, {@code "00001"}, successful completion with the
   * result omitted, for one that had none to give back.
   *
   * <p>This is the half of the GQLSTATUS envelope a program reading rows and
   * failures could not see. The status a call returned says whether it
   * worked; this says which way, in the standard's own terms, and it is the
   * value a conformance harness grades.
   *
   * @return the code, never null
   */
  public String gqlstatus() {
    return zu.resultGqlstatus(open());
  }

  /**
   * The conditions the statement raised and carried on through.
   *
   * <p>An exception replaces a result and arrives as a throw; a warning rides
   * along with one, because a statement that dropped a null out of an
   * aggregate still has rows to give you and the standard still wants you
   * told. Almost every statement raises none.
   *
   * @return the records, in the order they were raised, unmodifiable and
   *     usually empty
   */
  public List<Diagnostic> notices() {
    long h = open();
    int count = zu.resultNotices(h);
    if (count == 0) {
      return List.of();
    }
    List<Diagnostic> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Diagnostic d = zu.resultNotice(h, i);
      if (d == null) {
        break;
      }
      out.add(d);
    }
    return Collections.unmodifiableList(out);
  }

  // ---- whole columns ----

  /**
   * A whole column of integers, borrowed from the engine rather than copied.
   *
   * <p>Reading a million of them costs one call and no allocation. What it
   * costs is a lifetime: the buffer is valid until {@link #close()} and not
   * one statement longer.
   *
   * <p>Nulls read as zero, which {@link #valid(int)} tells apart. Booleans
   * read as 0 and 1. A node does not read here at all: an internal row number
   * is not an identity, and {@link #nodeOffsets(int)} is the call that says
   * so out loud.
   *
   * @param column the column, which must hold integers or booleans
   * @return a read-only view, empty when the result has no rows
   */
  public LongBuffer longs(int column) {
    checkColumn(column);
    LongBuffer b = zu.colLongs(open(), column, rows);
    return b == null ? LongBuffer.allocate(0).asReadOnlyBuffer() : b;
  }

  /**
   * A whole column of floats, borrowed rather than copied.
   *
   * @param column the column, which must hold floats or integers
   * @return a read-only view, empty when the result has no rows
   */
  public DoubleBuffer doubles(int column) {
    checkColumn(column);
    DoubleBuffer b = zu.colDoubles(open(), column, rows);
    return b == null ? DoubleBuffer.allocate(0).asReadOnlyBuffer() : b;
  }

  /**
   * A whole column of node row offsets, borrowed rather than copied.
   *
   * <p>The row offset is what identifies a node inside its table, and it
   * takes the table to make an identity, which {@link Row#get(int)} hands
   * over as a {@link Value.Node}. This is the bulk path for a column of nodes
   * that are all of one table.
   *
   * @param column the column, which must hold nodes
   * @return a read-only view, empty when the result has no rows
   */
  public LongBuffer nodeOffsets(int column) {
    checkColumn(column);
    LongBuffer b = zu.colNodeOffsets(open(), column, rows);
    return b == null ? LongBuffer.allocate(0).asReadOnlyBuffer() : b;
  }

  /**
   * Which values of a column are not null, one byte a row, borrowed rather
   * than copied.
   *
   * @param column the column
   * @return a read-only view where a nonzero byte is a value, empty when the
   *     result has no rows
   */
  public ByteBuffer valid(int column) {
    checkColumn(column);
    ByteBuffer b = zu.colValid(open(), column, rows);
    return b == null ? ByteBuffer.allocate(0).asReadOnlyBuffer() : b;
  }

  // ---- chunks ----

  /**
   * How many chunks this result has, which is the loop bound.
   *
   * @return the count, 0 for a result with no rows
   */
  public long chunkCount() {
    return zu.chunkCount(open());
  }

  /**
   * One chunk.
   *
   * @param index the chunk, counting from zero
   * @return the chunk
   */
  public Chunk chunk(long index) {
    long h = open();
    long count = zu.chunkCount(h);
    if (index < 0 || index >= count) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE, "chunk " + index + " of a result with " + count + " of them"));
    }
    long[] shape = zu.chunk(h, index);
    return new Chunk(this, index, shape[0], shape[1]);
  }

  /**
   * Every chunk, in order.
   *
   * <p>Which of these to use is a question of size, and only for the columns
   * the engine did not fill. On those, the whole-column call converts all of
   * the column before returning any of it and keeps the conversion until the
   * result is freed, so reading the first hundred rows of a million-row
   * column and stopping pays for the other 999,900. On a column the engine
   * filled, which is every plan whose projection is a scan of stored values,
   * both calls are views of the buffer it wrote and neither converts
   * anything, so the choice is about the shape of the reading loop and
   * nothing else.
   *
   * @return the chunks
   */
  public Stream<Chunk> chunks() {
    long count = chunkCount();
    return java.util.stream.LongStream.range(0, count).mapToObj(this::chunk);
  }

  // ---- arrow ----

  /**
   * Hands the whole result to an Arrow consumer through the C Data Interface
   * and spends it, in batches of {@link #DEFAULT_BATCH} rows.
   *
   * @param stream the address of an {@code ArrowArrayStream} the caller owns
   *     and has not initialised
   */
  public void exportArrow(long stream) {
    exportArrow(stream, 0);
  }

  /**
   * Hands the whole result to an Arrow consumer through the C Data Interface
   * and spends it.
   *
   * <p>This is the low-level door, and most programs want {@code zudb-arrow}
   * rather than this: that module wraps this call in the {@code ArrowReader}
   * arrow-java already knows how to read, and it is a separate artifact so
   * that a program with no use for Arrow does not carry the dependency. What
   * is here is what that module needs and what a program with its own Arrow
   * bindings can use instead.
   *
   * <p>The two arguments are checked here rather than by the engine, so a call
   * refused for a stream that is nowhere or a batch of fewer than no rows
   * handed nothing over and spent nothing: that result is still there to read
   * the ordinary way, or to export once the argument is right.
   *
   * <p>Nothing on this path is a copy. The arrays that cross are the buffers
   * the executor filled, at the addresses it filled them at, which is why the
   * result is spent: after the buffers have left there is nothing here to
   * read a second time. So this result is closed whatever the call answered,
   * including a refusal, every buffer a columnar reader handed out before it
   * now belongs to the Arrow consumer, and closing it again afterwards is the
   * no-op it always was.
   *
   * <p>A node column names its table out of the catalog the connection holds.
   * A connection that has already closed is not a failure here, and the
   * export then names a table after its id, which is still an answer for a
   * program that kept a result and let its connection go.
   *
   * @param stream the address of an {@code ArrowArrayStream} the caller owns
   *     and has not initialised, written only on success and released through
   *     its own release callback rather than by anything here
   * @param rowsPerBatch how many rows a consumer sees at a time, or zero for
   *     {@link #DEFAULT_BATCH}. The batches are slices of arrays that are
   *     already in memory, so this is about what a consumer likes to work in
   *     and not about what gets allocated
   * @throws ZuProgrammingException if the stream address is zero, the batch
   *     is negative, or a column holds something Arrow has no type for
   * @throws ZuClosedException if this result is already closed
   */
  public void exportArrow(long stream, long rowsPerBatch) {
    long h = open();
    if (stream == 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(Status.MISUSE, "the stream to export into is null"));
    }
    if (rowsPerBatch < 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(Status.MISUSE, "a batch of fewer than no rows: " + rowsPerBatch));
    }
    // The handle goes before the call rather than after it. The engine nulls
    // the result on every path it takes, refusals included, so a result this
    // still thought it owned would be one a later close would free twice.
    handle.set(0);
    zu.resultArrow(conn == null ? 0 : conn.lend(), h, rowsPerBatch, stream);
  }

  /**
   * Whether this result has been closed.
   *
   * @return true once {@link #close()} has run
   */
  public boolean isClosed() {
    return handle.get() == 0;
  }

  /**
   * Releases the rows and everything borrowed from them: every buffer, every
   * string, every {@link Value}. Closing twice does nothing the second time.
   */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.resultFree(h);
    }
  }

  // ---- the parts a Row and a Chunk use ----

  ZuBinding zu() {
    return zu;
  }

  long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(Diagnostic.misuse(Status.MISUSE_CLOSED, "this result is closed"));
    }
    return h;
  }

  void checkColumn(int column) {
    if (column < 0 || column >= columns) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "column " + column + " of a result with " + columns + " of them: "
                  + String.join(", ", names)));
    }
  }

  /**
   * The value tree under one cell, built once and owned by the caller.
   *
   * <p>Every string in it is copied out on the way, so the tree outlives
   * nothing that the result does not, and a caller holding one after the
   * result closed is holding Java objects rather than freed memory. That is
   * the one place this client copies on purpose: a tree of records is not a
   * column, and the alternative is a lifetime rule with no way to enforce it.
   */
  Value read(long value) {
    Type type = Type.of(zu.valueType(value));
    switch (type) {
      case NULL:
        return Value.Null.instance();
      case BOOL:
        return new Value.Bool(zu.valueBoolean(value));
      case INT:
        return new Value.Int(zu.valueLong(value));
      case FLOAT:
        return new Value.Float(zu.valueDouble(value));
      case STR:
        return new Value.Str(zu.valueString(value));
      case NODE: {
        long[] n = zu.valueNode(value);
        return new Value.Node((int) n[0], n[1]);
      }
      case REL: {
        long[] r = zu.valueRel(value);
        return new Value.Rel((int) r[0], r[1], r[2]);
      }
      case LIST:
        return new Value.List(items(value));
      case PATH:
        return new Value.Path(items(value));
      case RECORD: {
        long length = zu.valueLength(value);
        List<Value.Field> fields = new ArrayList<>((int) length);
        for (long i = 0; i < length; i++) {
          fields.add(new Value.Field(zu.valueField(value, i), read(zu.valueAt(value, i))));
        }
        return new Value.Record(Collections.unmodifiableList(fields));
      }
      case TEMPORAL: {
        long[] t = zu.valueTemporal(value);
        return new Value.Temporal(Value.Temporal.Kind.of((int) t[0]), t[1], (int) t[2]);
      }
      case GRAPH:
        return new Value.Graph();
      case BINDING_TABLE:
      default:
        return new Value.BindingTable();
    }
  }

  private List<Value> items(long value) {
    long length = zu.valueLength(value);
    List<Value> out = new ArrayList<>((int) length);
    for (long i = 0; i < length; i++) {
      out.add(read(zu.valueAt(value, i)));
    }
    return Collections.unmodifiableList(out);
  }
}
