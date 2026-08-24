package dev.zudb.corpus;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Row;
import dev.zudb.Statement;
import dev.zudb.Value;
import dev.zudb.ZuException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Running the corpus through this client, and saying what happened in the
 * form the other eight runners are compared against.
 *
 * <p>Each case gets a database of its own. Cases in a suite are written as
 * if nothing came before them, and the cheapest way to keep that true is to
 * make it true: a case that leaked a table into the next one would be a
 * failure that moves when the file is reordered, which is the worst kind to
 * be handed.
 *
 * <p>An outcome is one of three things and not two. Passed and failed are
 * obvious. Unsupported is the third, and it exists because the corpus is
 * versioned with the engine and shipped to nine clients that will not all
 * implement the same subset at the same time: a client that cannot yet
 * parse a statement should say so, and a report should be able to tell that
 * apart from an answer that came back wrong.
 *
 * <p>What this prints is what the Rust runner prints, line for line, so
 * that a disagreement between two clients is a diff and not a reading
 * exercise.
 */
public final class Runner {

  private Runner() {}

  /** What one case came to. */
  public enum Outcome {
    /** The statement answered what the case wants. */
    PASSED,
    /** It answered something else. */
    FAILED,
    /**
     * The engine does not implement the statement yet, which the corpus
     * allows on purpose: the cases are the contract and the engine catches
     * up to them.
     */
    UNSUPPORTED;

    /**
     * How a report spells an outcome, which is the reference runner's
     * spelling and not a word of it different.
     */
    String mark() {
      return switch (this) {
        case PASSED -> "ok";
        case FAILED -> "FAILED";
        default -> "unsupported";
      };
    }
  }

  /**
   * What one case did, with the account of why when it did not pass.
   *
   * @param suite the name of the suite the case is in
   * @param name the case's name within that suite
   * @param line where the case starts in its file
   * @param outcome what it came to
   * @param detail what went wrong, in enough detail to fix the case or the
   *     engine without running it again
   */
  public record Ran(String suite, String name, int line, Outcome outcome, String detail) {

    /** The line a report prints for one case. */
    @Override
    public String toString() {
      String head = suite + "/" + name + " line " + line + " " + outcome.mark();
      return detail.isEmpty() ? head : head + ": " + detail;
    }
  }

  /**
   * What a whole run did.
   *
   * @param ran one entry per case, in the order the cases were run
   */
  public record Report(List<Ran> ran) {

    /**
     * How many cases came to one outcome.
     *
     * @param outcome the one to count
     * @return how many
     */
    public int count(Outcome outcome) {
      int n = 0;
      for (Ran one : ran) {
        if (one.outcome() == outcome) {
          n++;
        }
      }
      return n;
    }

    /**
     * One line saying what the run came to, which is what a CI log keeps
     * and what two runs are compared by.
     *
     * @return the line
     */
    public String summary() {
      return ran.size() + " cases, " + count(Outcome.PASSED) + " passed, "
          + count(Outcome.FAILED) + " failed, " + count(Outcome.UNSUPPORTED) + " unsupported";
    }
  }

  /**
   * Runs every case of every suite, in the order they were written.
   *
   * @param suites what to run
   * @param directory one the runner may make databases under. Each case
   *     gets its own file in it, named after the case, so that a failure
   *     leaves something to open
   * @return what every case did
   */
  public static Report run(List<Suite> suites, Path directory) {
    List<Ran> out = new ArrayList<>();
    for (Suite suite : suites) {
      for (Suite.Case one : suite.cases()) {
        Ran ran = runCase(suite, one, directory);
        // A failure leaves its database behind, which is the one thing
        // somebody reading the report will want to open. Everything else
        // goes as it finishes, because a corpus of fourteen hundred cases
        // is fourteen hundred files and holding them all until the run ends
        // is gigabytes of a disk that has other work to do. The Rust and C
        // runners do the same.
        if (ran.outcome() != Outcome.FAILED) {
          Path path = casePath(directory, suite.name(), one.name());
          remove(path);
          // The WAL sidecar goes with it. A database is <db> and its log is
          // <db>.wal, and a log left beside a name the next run creates
          // again is a log that run would adopt.
          remove(path.resolveSibling(path.getFileName() + ".wal"));
        }
        out.add(ran);
      }
    }
    return new Report(List.copyOf(out));
  }

  private static void remove(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // A file that would not go is a full disk or a permission, and
      // neither is a thing to say about the case that just ran.
    }
  }

  private static Path casePath(Path directory, String suite, String name) {
    return directory.resolve(suite + "-" + name + ".zu");
  }

  private static Ran runCase(Suite suite, Suite.Case one, Path directory) {
    Path path = casePath(directory, suite.name(), one.name());

    // The load goes in before the connection opens, because it is bulk load
    // and bulk load is the path that builds the file rather than one that
    // goes through a statement. Every case of the suite gets its own copy
    // of it for the same reason every case gets its own database.
    //
    // A loader makes the file, so the two halves of this are the two ways a
    // database comes into being in this client and a case has exactly one
    // of them.
    if (suite.load() != null) {
      try {
        applyLoad(suite.load(), path);
      } catch (ZuException | CorpusException e) {
        return ran(suite, one, Outcome.FAILED, "the suite's load: " + errorText(e));
      }
    } else {
      try {
        Database.create(path).close();
      } catch (ZuException e) {
        return ran(suite, one, Outcome.FAILED, "creating " + path + ": " + errorText(e));
      }
    }

    Database db;
    try {
      db = Database.open(path);
    } catch (ZuException e) {
      return ran(suite, one, Outcome.FAILED, "opening " + path + ": " + errorText(e));
    }
    try {
      Connection main;
      try {
        main = db.connect();
      } catch (ZuException e) {
        return ran(suite, one, Outcome.FAILED, "opening " + path + ": " + errorText(e));
      }
      List<Named> open = new ArrayList<>();
      open.add(new Named(Suite.MAIN, main));
      try {
        return statement(suite, one, open);
      } finally {
        // In reverse, so that the connection the case was opened with is
        // the last one to go, which is the order the ones after it were
        // made from it in.
        for (int i = open.size() - 1; i >= 0; i--) {
          open.get(i).conn().close();
        }
      }
    } finally {
      db.close();
    }
  }

  private static Ran statement(Suite suite, Suite.Case one, List<Named> open) {
    for (int i = 0; i < one.setup().size(); i++) {
      Suite.Step step = one.setup().get(i);
      Connection on;
      try {
        on = connection(open, step.on());
      } catch (ZuException e) {
        return ran(suite, one, Outcome.FAILED,
            "connecting as " + Text.quote(step.on()) + ": " + errorText(e));
      }
      try {
        on.execute(step.query());
      } catch (ZuException e) {
        // A setup that fails is not a result about the statement under
        // test, so it is never a pass and never a quiet skip.
        if (unsupported(e)) {
          return ran(suite, one, Outcome.UNSUPPORTED, "setup " + (i + 1) + ": " + errorText(e));
        }
        return ran(suite, one, Outcome.FAILED,
            "setup " + (i + 1) + " failed: " + errorText(e));
      }
    }

    Connection on;
    try {
      on = connection(open, one.on());
    } catch (ZuException e) {
      return ran(suite, one, Outcome.FAILED,
          "connecting as " + Text.quote(one.on()) + ": " + errorText(e));
    }

    Statement prepared = null;
    Result result;
    try {
      if (one.params().isEmpty()) {
        result = on.query(one.query());
      } else {
        prepared = on.prepare(one.query());
        for (Suite.Param param : one.params()) {
          bind(prepared, param);
        }
        result = prepared.execute();
      }
    } catch (ZuException | CorpusException e) {
      if (prepared != null) {
        prepared.close();
      }
      if (!one.raises().isEmpty()) {
        String code = e instanceof ZuException zu ? zu.code().orElse("") : "";
        if (code.isEmpty()) {
          return ran(suite, one, Outcome.FAILED, "failed with no GQLSTATUS where the case wants "
              + one.raises() + ": " + errorText(e));
        }
        if (code.equals(one.raises())) {
          return ran(suite, one, Outcome.PASSED, "");
        }
        return ran(suite, one, Outcome.FAILED, "raised " + code + " where the case wants "
            + one.raises() + ": " + errorText(e));
      }
      if (e instanceof ZuException zu && unsupported(zu)) {
        return ran(suite, one, Outcome.UNSUPPORTED, errorText(e));
      }
      return ran(suite, one, Outcome.FAILED, errorText(e));
    }
    try {
      if (!one.raises().isEmpty()) {
        return ran(suite, one, Outcome.FAILED,
            "returned rows where the case wants " + one.raises());
      }
      // The catalog after the statement rather than before it, because a
      // statement may have made the table the rows it returns are rows of.
      IntFunction<String> tables = table -> tableName(on, table);
      List<String> columns = result.columnNames();
      List<List<Cell>> got;
      try {
        got = readAll(result, tables);
      } catch (ZuException e) {
        return ran(suite, one, Outcome.FAILED, errorText(e));
      }
      String detail = compare(one.columns(), one.rows(), columns, got);
      if (!detail.isEmpty()) {
        return ran(suite, one, Outcome.FAILED, detail);
      }
      // The export is checked on the result the rows were read from rather
      // than on a second run of the statement, because it is the same
      // result a client exports: one statement, two ways of reading what it
      // gave back.
      detail = exported(one.arrow(), result, got.size());
      if (!detail.isEmpty()) {
        return ran(suite, one, Outcome.FAILED, detail);
      }
      return ran(suite, one, Outcome.PASSED, "");
    } finally {
      // An export spends the result and closes it, and closing it again is
      // the no-op it always was.
      result.close();
      if (prepared != null) {
        prepared.close();
      }
    }
  }

  private static Ran ran(Suite suite, Suite.Case one, Outcome outcome, String detail) {
    return new Ran(suite.name(), one.name(), one.line(), outcome, detail);
  }

  /**
   * A table's name, or its id when there is no name to be had.
   *
   * <p>A table with no name is spelled after its id, which is what a node
   * column of an export is named when there is no catalog to ask. It should
   * not happen with the connection right here, and it is a spelling rather
   * than a stop because a report saying the case wants person#1 and this is
   * #7 is more use to whoever has to fix it than one that gave up.
   */
  private static String tableName(Connection on, int table) {
    try {
      String name = on.tableName(table);
      if (name != null && !name.isEmpty()) {
        return name;
      }
    } catch (ZuException e) {
      // The id below, which is still an answer.
    }
    return "#" + Integer.toUnsignedString(table);
  }

  /** One open connection and the name the case calls it by. */
  private record Named(String name, Connection conn) {}

  /**
   * The connection a case named, made if this is the first mention of it.
   *
   * <p>A new one is a duplicate of the case's own rather than a second open
   * of the file, which is what a pool does: the two share the write side,
   * so each sees what the other has committed. Opening the path twice would
   * be two databases that happen to be the same file, which is a different
   * thing and not what a case about a transaction means.
   */
  private static Connection connection(List<Named> open, String name) {
    for (Named had : open) {
      if (had.name().equals(name)) {
        return had.conn();
      }
    }
    Connection made = open.get(0).conn().duplicate();
    open.add(new Named(name, made));
    return made;
  }

  /**
   * One parameter, handed to the binding call that takes its type.
   *
   * <p>A shape with no call here is one the corpus can write and this
   * client cannot bind, and it says so by name rather than by putting the
   * value somewhere it does not belong.
   */
  private static void bind(Statement prepared, Suite.Param param) {
    String name = param.name();
    switch (param.value()) {
      case Cell.Null _ -> prepared.bindNull(name);
      case Cell.Bool value -> prepared.bind(name, value.value());
      case Cell.Int value -> prepared.bind(name, value.value());
      case Cell.Float value -> prepared.bind(name, value.value());
      case Cell.Str value -> prepared.bind(name, value.value());
      case Cell.Time value -> prepared.bind(name, value.value());
      default -> throw Text.refuse("a parameter of %s, which this client has no binding call for",
          Values.show(param.value()));
    }
  }

  /**
   * Every row of a result, in the corpus's own shape.
   *
   * <p>The whole result is read before anything is compared, because the
   * engine hands back an array rather than a cursor and a comparison that
   * stopped at the first difference would leave the rest unread anyway.
   */
  private static List<List<Cell>> readAll(Result result, IntFunction<String> tables) {
    int columns = result.columns();
    long count = result.rows();
    List<List<Cell>> out = new ArrayList<>((int) count);
    for (long i = 0; i < count; i++) {
      Row row = result.row(i);
      List<Cell> values = new ArrayList<>(columns);
      for (int j = 0; j < columns; j++) {
        // Into the corpus's own shape here rather than at the comparison,
        // because the engine's edge carries a field the corpus does not
        // write and its node carries an id where a case writes a name.
        values.add(Cell.of(row.get(j), tables));
      }
      out.add(List.copyOf(values));
    }
    return List.copyOf(out);
  }

  /**
   * Puts the suite's load in through this client's own bulk load path,
   * which is the strongest form of the corpus question: the value crosses
   * the boundary twice and by two different mechanisms.
   */
  private static void applyLoad(Suite.Load load, Path path) {
    try (Loader loader = Loader.create(path)) {
      loader.table(load.nodes(), load.edges(), load.count());
      for (Suite.Column column : load.columns()) {
        loadColumn(loader, column);
      }
      if (!load.pairs().isEmpty()) {
        int[] from = new int[load.pairs().size()];
        int[] to = new int[load.pairs().size()];
        for (int i = 0; i < load.pairs().size(); i++) {
          from[i] = load.pairs().get(i).from();
          to[i] = load.pairs().get(i).to();
        }
        loader.edges(from, to);
      }
      loader.finish();
    }
  }

  /**
   * Hands one column to the method that takes its type.
   *
   * <p>The loader has a method per column type rather than one that takes
   * an Object, which is what makes a load a real test of the encoding: a
   * column of dates goes in as dates. A type with no method here is a
   * column the corpus has never written, and it says so by name rather than
   * by putting the values somewhere they do not belong.
   */
  private static void loadColumn(Loader loader, Suite.Column column) {
    String name = column.name();
    List<Cell> values = column.values();
    switch (column.type()) {
      case "STRING" -> loader.column(name, strings(values));
      case "BOOL" -> loader.column(name, bools(values));
      case "FLOAT32", "FLOAT64" -> loader.column(name, doubles(values));
      case "DATE" -> loader.temporalColumn(name, Value.Temporal.Kind.DATE, counts(values));
      case "LOCALTIME" ->
          loader.temporalColumn(name, Value.Temporal.Kind.LOCAL_TIME, counts(values));
      case "LOCALDATETIME" ->
          loader.temporalColumn(name, Value.Temporal.Kind.LOCAL_DATETIME, counts(values));
      case "DURATION" ->
          // The two duration kinds are two columns as far as the loader is
          // concerned, and a column is one or the other, so which one it is
          // comes off the first value.
          loader.temporalColumn(name, kind(values.get(0)), counts(values));
      default -> {
        if (Values.integer(column.type())) {
          loader.column(name, longs(values));
        } else {
          throw Text.refuse("a load column of %s, which this client has no loader method for",
              column.type());
        }
      }
    }
  }

  // A column of decoded values as the array one loader method takes. A
  // value of the wrong shape cannot happen: every value in a column went
  // through the same type's parser.

  private static String[] strings(List<Cell> values) {
    String[] out = new String[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Cell.Str) values.get(i)).value();
    }
    return out;
  }

  private static boolean[] bools(List<Cell> values) {
    boolean[] out = new boolean[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Cell.Bool) values.get(i)).value();
    }
    return out;
  }

  private static double[] doubles(List<Cell> values) {
    double[] out = new double[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Cell.Float) values.get(i)).value();
    }
    return out;
  }

  private static long[] longs(List<Cell> values) {
    long[] out = new long[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Cell.Int) values.get(i)).value();
    }
    return out;
  }

  private static long[] counts(List<Cell> values) {
    long[] out = new long[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Cell.Time) values.get(i)).value().count();
    }
    return out;
  }

  private static Value.Temporal.Kind kind(Cell value) {
    return ((Cell.Time) value).value().kind();
  }

  /**
   * What the export gave that the case did not want, or the empty string
   * when the case says nothing about it and when the two agree.
   *
   * <p>A result Arrow has no type for is a refusal from the export rather
   * than a condition from the statement, so a case saying {@code refused}
   * is the case where the stream failing to open is the right answer.
   */
  private static String exported(Arrow.Export want, Result result, int count) {
    if (want == null) {
      return "";
    }
    Arrow.Exported got;
    try {
      got = Arrow.exported(result);
    } catch (ArrowException e) {
      return want.refused() ? "" : "arrow refused the result: " + errorText(e);
    }
    if (want.refused()) {
      return "arrow exported the result where the case wants a refusal";
    }
    String detail = Arrow.schemaSays(got.fields(), want.fields());
    if (!detail.isEmpty()) {
      return detail;
    }
    if (got.rows() != count) {
      return "arrow gives " + got.rows() + " rows where the case wants " + count;
    }
    return "";
  }

  /**
   * Whether a condition means the engine does not implement the statement
   * rather than that the statement is wrong.
   *
   * <p>The two GQL classes that say so are 42, syntax error or access rule
   * violation, and 0A, feature not supported. A case landing on either is a
   * case ahead of the engine, which the corpus allows on purpose: the cases
   * are the contract and the engine catches up to them.
   */
  private static boolean unsupported(ZuException e) {
    String code = e.code().orElse("");
    return code.startsWith("42") || code.startsWith("0A");
  }

  /**
   * What the engine said, which is what the Rust runner prints for the same
   * failure.
   *
   * <p>The message rather than the exception, which is what a Java program
   * logs: a class name in front of every failing line would differ from the
   * report this one is diffed against. The message a condition carries
   * already opens with its own code, which is why nothing is added here
   * either.
   */
  static String errorText(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.toString() : message;
  }

  /**
   * What differs between what a case wants and what came back, or the empty
   * string if nothing does.
   *
   * <p>It reports the first difference rather than all of them, because the
   * first is nearly always the cause of the rest, and a report that prints
   * a hundred rows is one nobody reads to the end. The order the checks run
   * in is the reference runner's, so that two runners looking at the same
   * wrong answer say the same thing about it.
   */
  static String compare(List<String> wantColumns, List<List<Cell>> wantRows,
      List<String> gotColumns, List<List<Cell>> gotRows) {
    if (!wantColumns.equals(gotColumns)) {
      return "columns " + names(gotColumns) + " where the case wants " + names(wantColumns);
    }
    for (int i = 0; i < wantRows.size() && i < gotRows.size(); i++) {
      List<Cell> want = wantRows.get(i);
      List<Cell> got = gotRows.get(i);
      for (int j = 0; j < want.size() && j < got.size(); j++) {
        if (want.get(j).equals(got.get(j))) {
          continue;
        }
        String name = j < wantColumns.size() ? wantColumns.get(j) : "?";
        return "row " + (i + 1) + " column " + name + " is " + Values.show(got.get(j))
            + " where the case wants " + Values.show(want.get(j));
      }
    }
    if (wantRows.size() != gotRows.size()) {
      return gotRows.size() + " rows where the case wants " + wantRows.size();
    }
    return "";
  }

  /** A list of column names the way Rust's {@code {:?}} writes one. */
  private static String names(List<String> columns) {
    StringBuilder out = new StringBuilder("[");
    String between = "";
    for (String name : columns) {
      out.append(between).append('"').append(name).append('"');
      between = ", ";
    }
    return out.append(']').toString();
  }
}
