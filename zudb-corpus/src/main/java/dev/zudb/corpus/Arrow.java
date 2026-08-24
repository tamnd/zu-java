package dev.zudb.corpus;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.zudb.Result;
import dev.zudb.ZuException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * What a result looks like on the way out through Arrow.
 *
 * <p>A client that reads rows one at a time and a client that exports a
 * million of them to a dataframe are the same client, and only one of those
 * paths is covered by a case that asserts values. The other one has its own
 * contract: a column of dates is a Date32 and not a string of digits, a
 * year-month duration is a month-day-nano interval because that is the
 * interval every reader implements, a node is a struct of the name of its
 * table and the row it is, and a time with an offset is refused rather than
 * quietly moved to UTC. None of that shows up in a row a case compares.
 *
 * <p>So a case may say what the export gives as well as what the rows are,
 * and the runner checks both against one statement. What it checks is the
 * schema, field by field and into the nested types, and how many rows came
 * back through the stream. The schema is spelled in the C Data Interface's
 * own format strings, {@code l} for an int64 and {@code +s} for a struct,
 * because that is the one spelling every language sees the same.
 *
 * <p>The schema is read off the interface itself through
 * {@link java.lang.foreign} rather than out of arrow-java, for two reasons.
 * The first is that a checkout running the corpus should pull nothing, and
 * arrow-java is a tree. The second is that the C Data Interface is what the
 * C runner and the Go runner have at this point too, so all three read the
 * same bytes and report them in the same words, which is the whole reason a
 * format string is what the case writes down. Mapping arrow-java's own
 * ArrowType back to a format string would be a third opinion about the
 * spelling, and a third opinion is what this class exists to avoid.
 *
 * <p>Values are not read back here. A consumer that decoded every array by
 * hand in each of nine languages would be nine new decoders under test,
 * which is more of our own code and not more of the contract; the rows the
 * case already asserts are the same values by another road.
 */
public final class Arrow {

  /**
   * How a report names the whole result, which is the place the columns of
   * an export are in.
   */
  public static final String THE_RESULT = "the result";

  /**
   * One field of the schema an export gives, and the fields under it when it
   * is a struct or a list.
   *
   * <p>A list has exactly one field under it, which Arrow names
   * {@code item}, and a case writes that out rather than leaving it implied:
   * a client that named it {@code element} would export something no reader
   * lines up with what another client wrote.
   *
   * @param name the field's name, which for a column is the column's name
   *     and for the field under a list is {@code item}
   * @param format the C Data Interface format string, {@code l} for an
   *     int64, {@code u} for a string, {@code tsn:} for a timestamp in
   *     nanoseconds with no zone
   * @param children the fields under this one, empty for everything that is
   *     not a struct or a list
   */
  public record Field(String name, String format, List<Field> children) {

    /**
     * One field with nothing under it.
     *
     * @param name the field's name
     * @param format the format string
     */
    public Field(String name, String format) {
      this(name, format, List.of());
    }
  }

  /**
   * What a case says about the way out through Arrow: the columns it gives,
   * or that Arrow has no type for one of them.
   *
   * <p>A refusal is a thing a statement can produce today, which is why it
   * has a spelling of its own rather than being a bug.
   *
   * @param refused whether the case says the export says no, in which case
   *     there are no fields to compare
   * @param fields the schema's fields, one per column, in order
   */
  public record Export(boolean refused, List<Field> fields) {}

  /**
   * What an export gave: the columns and how many rows came out.
   *
   * @param fields the fields under the stream's top level struct, which is
   *     one per column
   * @param rows how many rows the batches held between them
   */
  public record Exported(List<Field> fields, long rows) {}

  // The C Data Interface, laid out here rather than taken from anywhere: it
  // is nine fields that have not changed since Arrow 0.17 and every producer
  // in the world writes exactly this, which is what makes it an ABI.
  // Copying it is how every consumer of it starts.
  private static final MemoryLayout SCHEMA = MemoryLayout.structLayout(
      ADDRESS.withName("format"),
      ADDRESS.withName("name"),
      ADDRESS.withName("metadata"),
      JAVA_LONG.withName("flags"),
      JAVA_LONG.withName("n_children"),
      ADDRESS.withName("children"),
      ADDRESS.withName("dictionary"),
      ADDRESS.withName("release"),
      ADDRESS.withName("private_data"));

  // Only the length is read off an array: the values a case cares about it
  // already asserts as rows.
  private static final MemoryLayout ARRAY = MemoryLayout.structLayout(
      JAVA_LONG.withName("length"),
      JAVA_LONG.withName("null_count"),
      JAVA_LONG.withName("offset"),
      JAVA_LONG.withName("n_buffers"),
      JAVA_LONG.withName("n_children"),
      ADDRESS.withName("buffers"),
      ADDRESS.withName("children"),
      ADDRESS.withName("dictionary"),
      ADDRESS.withName("release"),
      ADDRESS.withName("private_data"));

  private static final MemoryLayout STREAM = MemoryLayout.structLayout(
      ADDRESS.withName("get_schema"),
      ADDRESS.withName("get_next"),
      ADDRESS.withName("get_last_error"),
      ADDRESS.withName("release"),
      ADDRESS.withName("private_data"));

  private static final VarHandle SCHEMA_FORMAT = at(SCHEMA, "format");
  private static final VarHandle SCHEMA_NAME = at(SCHEMA, "name");
  private static final VarHandle SCHEMA_CHILDREN = at(SCHEMA, "children");
  private static final VarHandle SCHEMA_RELEASE = at(SCHEMA, "release");
  private static final VarHandle SCHEMA_KIDS = at(SCHEMA, "n_children");

  private static final VarHandle ARRAY_LENGTH = at(ARRAY, "length");
  private static final VarHandle ARRAY_RELEASE = at(ARRAY, "release");

  private static final VarHandle STREAM_GET_SCHEMA = at(STREAM, "get_schema");
  private static final VarHandle STREAM_GET_NEXT = at(STREAM, "get_next");
  private static final VarHandle STREAM_LAST_ERROR = at(STREAM, "get_last_error");
  private static final VarHandle STREAM_RELEASE = at(STREAM, "release");

  private static final Linker LINKER = Linker.nativeLinker();
  // int (*)(struct*, struct*), which both get_schema and get_next are.
  private static final FunctionDescriptor TAKES = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  // const char* (*)(struct*), which get_last_error is.
  private static final FunctionDescriptor SAYS = FunctionDescriptor.of(ADDRESS, ADDRESS);
  // void (*)(struct*), which every release is.
  private static final FunctionDescriptor FREES = FunctionDescriptor.ofVoid(ADDRESS);

  private Arrow() {}

  /**
   * Reads the {@code arrow:} of a case.
   *
   * @param node what was written under the key
   * @return what the case says the export gives
   * @throws CorpusException if it is not a shape an export has
   */
  public static Export parseExport(Node node) {
    String text = node.text();
    if (text != null) {
      if (text.equals("refused")) {
        return new Export(true, List.of());
      }
      throw Text.refuse("line %d: `arrow:` is the columns the export gives, or `refused` for a "
          + "result Arrow has no type for, and this is %s", node.line(), Text.quote(text));
    }
    return new Export(false, exportFields(node));
  }

  private static List<Field> exportFields(Node node) {
    List<Node> items = node.seq();
    if (items == null) {
      throw Text.refuse("line %d: `arrow:` is a sequence of fields, and this is %s",
          node.line(), node.what());
    }
    List<Field> out = new ArrayList<>(items.size());
    for (Node item : items) {
      out.add(exportField(item));
    }
    return List.copyOf(out);
  }

  private static Field exportField(Node node) {
    int at = node.line();
    if (node.map() == null) {
      throw Text.refuse("line %d: an Arrow field is a mapping of `name` and `format`, and this "
          + "is %s", at, node.what());
    }
    List<String> unknown = node.unknown("name", "format", "children");
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: an Arrow field has no key %s", at, Text.quote(unknown.get(0)));
    }
    String name = spelled(node, "name", at);
    String format = spelled(node, "format", at);
    if (format.isEmpty()) {
      throw Text.refuse("line %d: an empty format string is not a type Arrow has", at);
    }
    List<Field> children = List.of();
    Node under = node.get("children");
    if (under != null) {
      children = exportFields(under);
    }
    // A nested format is the one thing about a format string this reader
    // knows, and it is worth knowing here: a case that wrote the fields of a
    // struct under a "u" would be asserting something the export cannot
    // produce, and finding that out at load time says so with a line number
    // rather than as a failure in a report.
    boolean nested = format.charAt(0) == '+';
    if (nested && children.isEmpty()) {
      throw Text.refuse("line %d: %s is a nested type and the fields under it are part of it",
          at, Text.quote(format));
    }
    if (!nested && !children.isEmpty()) {
      throw Text.refuse("line %d: %s holds no fields, so nothing goes under it",
          at, Text.quote(format));
    }
    return new Field(name, format, children);
  }

  private static String spelled(Node node, String key, int at) {
    Node value = node.get(key);
    String text = value == null ? null : value.text();
    if (text == null) {
      throw Text.refuse("line %d: an Arrow field has a `%s:`", at, key);
    }
    return text;
  }

  /**
   * The columns a result gives through Arrow and how many rows came out of
   * the stream.
   *
   * <p>The stream is taken once and both answers come out of that one
   * taking, because a stream is consumed by reading it and a second export
   * would be a second statement in all but name.
   *
   * <p>A column Arrow cannot hold is found when the stream is asked for,
   * before a row moves, and it comes back as an {@link ArrowException} so
   * that the runner can tell it from a schema that came out wrong. The
   * result is spent either way, which is what {@link Result#exportArrow}
   * promises.
   *
   * @param result the result to export, which is closed by this call
   * @return the fields and the row count
   * @throws ArrowException if the export or the stream says no
   */
  public static Exported exported(Result result) {
    // Confined, because nothing here crosses a thread, and the arena is what
    // the C side calls the caller's own storage: the struct stays where it
    // is until this returns, and the engine's stream keeps its state behind
    // private_data rather than a pointer back to the struct.
    try (Arena arena = Arena.ofConfined()) {
      // Zeroed by the arena, which is what the interface asks of a caller.
      MemorySegment stream = arena.allocate(STREAM);
      try {
        result.exportArrow(stream.address());
      } catch (ZuException e) {
        throw new ArrowException(e.getMessage());
      }
      try {
        MemorySegment schema = arena.allocate(SCHEMA);
        int code = call(STREAM_GET_SCHEMA, stream, schema);
        if (code != 0) {
          throw new ArrowException(said(stream, code));
        }
        // The stream's schema is a struct of the columns, so what the case
        // is compared against is the fields under it.
        Field top = walked(schema);
        release(SCHEMA_RELEASE, schema);

        long count = 0;
        MemorySegment batch = arena.allocate(ARRAY);
        while (true) {
          batch.fill((byte) 0);
          code = call(STREAM_GET_NEXT, stream, batch);
          if (code != 0) {
            throw new ArrowException(said(stream, code));
          }
          // A released array is how the interface says the stream is done.
          if (empty((MemorySegment) ARRAY_RELEASE.get(batch, 0L))) {
            break;
          }
          count += (long) ARRAY_LENGTH.get(batch, 0L);
          release(ARRAY_RELEASE, batch);
        }
        return new Exported(top.children(), count);
      } finally {
        release(STREAM_RELEASE, stream);
      }
    }
  }

  /** One field of an exported schema, and everything under it. */
  @SuppressWarnings("restricted")
  private static Field walked(MemorySegment one) {
    String name = text((MemorySegment) SCHEMA_NAME.get(one, 0L));
    String format = text((MemorySegment) SCHEMA_FORMAT.get(one, 0L));
    long kids = (long) SCHEMA_KIDS.get(one, 0L);
    List<Field> children = new ArrayList<>((int) kids);
    if (kids > 0) {
      MemorySegment under = ((MemorySegment) SCHEMA_CHILDREN.get(one, 0L))
          .reinterpret(kids * ADDRESS.byteSize());
      for (long i = 0; i < kids; i++) {
        children.add(walked(under.getAtIndex(ADDRESS, i).reinterpret(SCHEMA.byteSize())));
      }
    }
    return new Field(name, format, List.copyOf(children));
  }

  /**
   * What the export gave that the case did not want, or the empty string
   * when the two agree.
   *
   * <p>The comparison walks the schema and the case's fields together and
   * stops at the first difference, for the reason the row comparison does:
   * the first is nearly always the cause of the rest.
   *
   * @param got what the export gave
   * @param want what the case wants
   * @return what is wrong, or the empty string
   */
  public static String schemaSays(List<Field> got, List<Field> want) {
    return fieldsUnder("", got, want);
  }

  // The fields under one place, where the place is the dotted path of the
  // field they are under and the empty one is the result itself.
  private static String fieldsUnder(String prefix, List<Field> got, List<Field> want) {
    String place = prefix.isEmpty() ? THE_RESULT : Text.quote(prefix);
    if (got.size() != want.size()) {
      return "arrow gives " + got.size() + " fields in " + place
          + " where the case wants " + want.size();
    }
    for (int i = 0; i < got.size(); i++) {
      Field mine = got.get(i);
      Field theirs = want.get(i);
      if (!mine.name().equals(theirs.name())) {
        return "arrow field " + (i + 1) + " in " + place + " is named "
            + Text.quote(mine.name()) + " where the case wants " + Text.quote(theirs.name());
      }
      // The path is the case's own names joined with dots, which is how a
      // field inside a path inside a column is pointed at without printing
      // the whole schema at somebody.
      String path = prefix.isEmpty() ? theirs.name() : prefix + "." + theirs.name();
      if (!mine.format().equals(theirs.format())) {
        return "arrow field " + Text.quote(path) + " is " + Text.quote(mine.format())
            + " where the case wants " + Text.quote(theirs.format());
      }
      String why = fieldsUnder(path, mine.children(), theirs.children());
      if (!why.isEmpty()) {
        return why;
      }
    }
    return "";
  }

  // ---- the interface itself ----

  private static VarHandle at(MemoryLayout layout, String field) {
    return layout.varHandle(PathElement.groupElement(field));
  }

  private static boolean empty(MemorySegment segment) {
    return segment == null || segment.address() == 0;
  }

  @SuppressWarnings("restricted")
  private static String text(MemorySegment segment) {
    return empty(segment) ? "" : segment.reinterpret(Long.MAX_VALUE).getString(0);
  }

  // A function pointer read out of the struct, called through the linker,
  // which is what the Go runner needs a static shim for and what the Python
  // runner writes a ctypes prototype for.
  @SuppressWarnings("restricted")
  private static int call(VarHandle which, MemorySegment stream, MemorySegment out) {
    MemorySegment fn = (MemorySegment) which.get(stream, 0L);
    if (empty(fn)) {
      throw new ArrowException("the stream has no callback where the interface requires one");
    }
    MethodHandle handle = LINKER.downcallHandle(fn, TAKES);
    try {
      return (int) handle.invokeExact(stream, out);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new ArrowException(String.valueOf(e.getMessage()));
    }
  }

  @SuppressWarnings("restricted")
  private static void release(VarHandle which, MemorySegment struct) {
    MemorySegment fn = (MemorySegment) which.get(struct, 0L);
    if (empty(fn)) {
      return;
    }
    MethodHandle handle = LINKER.downcallHandle(fn, FREES);
    try {
      handle.invokeExact(struct);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new ArrowException(String.valueOf(e.getMessage()));
    }
  }

  @SuppressWarnings("restricted")
  private static String said(MemorySegment stream, int code) {
    MemorySegment fn = (MemorySegment) STREAM_LAST_ERROR.get(stream, 0L);
    if (!empty(fn)) {
      MethodHandle handle = LINKER.downcallHandle(fn, SAYS);
      try {
        MemorySegment said = (MemorySegment) handle.invokeExact(stream);
        String message = text(said);
        if (!message.isEmpty()) {
          return message;
        }
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        // Fall through to the errno, which is still an answer.
      }
    }
    return "errno " + code;
  }
}
