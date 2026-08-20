package dev.zudb.spi;

import dev.zudb.Diagnostic;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

/**
 * The C ABI, as Java. One method per call in {@code zu.h}, named for it, and
 * nothing above it.
 *
 * <p>This is the whole of what a provider has to write. Everything a user
 * touches, {@code Database} through {@code Row}, is built on this once in the
 * API module rather than once per provider, which is what keeps two providers
 * from being two bindings that behave differently.
 *
 * <p>Handles are the C pointers, as {@code long}, and zero is null. That they
 * are numbers rather than objects is deliberate: it is the one representation
 * both a Panama downcall and a JNI call can pass without either of them
 * paying for the other's idea of a pointer, and the API module never sees one
 * it did not get from here.
 *
 * <h2>Failures</h2>
 *
 * <p>An implementation throws rather than returning a status. Every call that
 * can fail turns what the ABI answered into a {@link Diagnostic} and throws
 * {@link Diagnostic#toException()}, so the mapping from a status and a
 * GQLSTATUS code to an exception class happens once, here, in the API module.
 * The calls that can answer {@code ZU_DONE} say so in their own words below,
 * and none of them treats it as a failure.
 *
 * <h2>Lifetimes</h2>
 *
 * <p>Every buffer and every string an accessor returns belongs to the result
 * that produced it and is good until {@link #resultFree(long)}. A buffer is a
 * view of the engine's own memory and is not a copy: reading a column of a
 * million integers allocates nothing. The API module is what holds callers to
 * that rule; an implementation only has to return the view.
 *
 * <h2>Stability</h2>
 *
 * <p>This is a service provider interface and not part of the supported
 * surface. It moves with the C ABI, and a method is added to it whenever the
 * ABI grows one. Implement it if you are writing a provider; call it if you
 * are writing something this client has no room for. Do not build an
 * application on it.
 */
public interface ZuBinding {

  /**
   * What the loaded library calls itself, from {@code zu_version}.
   *
   * @return the engine version, never null
   */
  String version();

  // ---- databases ----

  /**
   * Opens an existing database.
   *
   * @param path the file
   * @param memoryLimit bytes the caches may hold, 0 for the default
   * @param threads query workers, 0 to let the executor pick, 1 for sequential
   * @param readOnly whether to open a descriptor this process cannot write through
   * @return the database handle
   */
  long databaseOpen(String path, long memoryLimit, long threads, boolean readOnly);

  /**
   * Creates a database and opens it. The path must not exist.
   *
   * @param path the file to make
   * @param memoryLimit bytes the caches may hold, 0 for the default
   * @param threads query workers, 0 to let the executor pick
   * @param readOnly whether to open a descriptor this process cannot write through
   * @return the database handle
   */
  long databaseCreate(String path, long memoryLimit, long threads, boolean readOnly);

  /**
   * Creates a database that never touches the filesystem.
   *
   * @param memoryLimit bytes the caches may hold, 0 for the default
   * @param threads query workers, 0 to let the executor pick
   * @param readOnly whether to refuse writes
   * @return the database handle
   */
  long databaseMemory(long memoryLimit, long threads, boolean readOnly);

  /**
   * Whether a database is in memory.
   *
   * @param db the database
   * @return true for a database in memory, false for one on disk
   */
  boolean databaseIsMemory(long db);

  /**
   * What this process calls the database, which for one in memory is a name
   * and not a path.
   *
   * @param db the database
   * @return the name, never null
   */
  String databasePath(long db);

  /**
   * Releases the path and the configuration. Connections opened from it are
   * not closed: each holds its own file handle.
   *
   * @param db the database, or zero
   */
  void databaseClose(long db);

  // ---- connections ----

  /**
   * A connection on a database.
   *
   * @param db the database
   * @return the connection handle
   */
  long connect(long db);

  /**
   * A second connection on the database a connection is already on, made
   * without a path.
   *
   * @param conn the connection
   * @return the new connection handle
   */
  long connDuplicate(long conn);

  /**
   * Closes a connection, rolling back a transaction still running.
   *
   * @param conn the connection, or zero
   */
  void connClose(long conn);

  /**
   * Stops whatever is running. The one call here meant to be made from
   * another thread while the connection is in use.
   *
   * @param conn the connection
   */
  void connInterrupt(long conn);

  /**
   * How many rows the statement has read out of storage, counted from zero at
   * each statement. Also safe from another thread.
   *
   * @param conn the connection
   * @return the count
   */
  long connRowsRead(long conn);

  /**
   * Whether a transaction is running, which no statement answers and every
   * host offering a block needs.
   *
   * @param conn the connection
   * @return true inside a transaction
   */
  boolean connInTransaction(long conn);

  /**
   * Starts a transaction.
   *
   * @param conn the connection
   * @param readOnly whether a write inside it is refused where it is written
   */
  void begin(long conn, boolean readOnly);

  /**
   * Keeps what the transaction wrote. Durable when this returns.
   *
   * @param conn the connection
   */
  void commit(long conn);

  /**
   * Unmakes what the transaction wrote.
   *
   * @param conn the connection
   */
  void rollback(long conn);

  // ---- statements ----

  /**
   * Runs one statement with no parameters.
   *
   * @param conn the connection
   * @param statement the text
   * @return the result handle
   */
  long query(long conn, String statement);

  /**
   * Prepares a statement. Bindings live on it and survive an execute, so a
   * loop rebinds only what changed.
   *
   * @param conn the connection
   * @param statement the text
   * @return the statement handle
   */
  long prepare(long conn, String statement);

  /**
   * Binds an integer.
   *
   * @param stmt the statement
   * @param name the parameter name, without its marker
   * @param value the value
   */
  void bindLong(long stmt, String name, long value);

  /**
   * Binds a float.
   *
   * @param stmt the statement
   * @param name the parameter name
   * @param value the value
   */
  void bindDouble(long stmt, String name, double value);

  /**
   * Binds a boolean.
   *
   * @param stmt the statement
   * @param name the parameter name
   * @param value the value
   */
  void bindBoolean(long stmt, String name, boolean value);

  /**
   * Binds a string.
   *
   * @param stmt the statement
   * @param name the parameter name
   * @param value the value
   */
  void bindString(long stmt, String name, String value);

  /**
   * Binds a temporal, as a kind and the count in the unit that kind implies.
   *
   * @param stmt the statement
   * @param name the parameter name
   * @param kind one of the {@code ZU_TEMPORAL_} values
   * @param count the count, in days for a date, months for a year-month
   *     duration, nanoseconds for the other five
   * @param offsetMinutes minutes east of UTC, ignored by every kind but the
   *     two zoned ones
   */
  void bindTemporal(long stmt, String name, int kind, long count, int offsetMinutes);

  /**
   * Binds null.
   *
   * @param stmt the statement
   * @param name the parameter name
   */
  void bindNull(long stmt, String name);

  /**
   * Runs a prepared statement with what is bound to it.
   *
   * @param stmt the statement
   * @return the result handle
   */
  long execute(long stmt);

  /**
   * Releases a statement. Safe after its connection closed.
   *
   * @param stmt the statement, or zero
   */
  void stmtClose(long stmt);

  // ---- result shape ----

  /**
   * How many rows.
   *
   * @param result the result
   * @return the count, 0 for a statement that answered with none
   */
  long resultRows(long result);

  /**
   * How many columns.
   *
   * @param result the result
   * @return the count
   */
  int resultCols(long result);

  /**
   * What a column is called.
   *
   * @param result the result
   * @param col the column, counting from zero
   * @return the name, never null
   */
  String resultColName(long result, int col);

  /**
   * The type tag of one cell.
   *
   * @param result the result
   * @param row the row, counting from zero
   * @param col the column, counting from zero
   * @return one of the {@code ZU_TYPE_} values
   */
  int resultCellType(long result, long row, int col);

  /**
   * One string cell.
   *
   * @param result the result
   * @param row the row
   * @param col the column, which must hold strings
   * @return the string, never null
   */
  String resultCellString(long result, long row, int col);

  /**
   * The completion condition of a statement that worked: {@code "00000"} for
   * one that answered with columns, {@code "00001"} for one that had none to
   * give back.
   *
   * @param result the result
   * @return the code, never null
   */
  String resultGqlstatus(long result);

  /**
   * How many conditions the statement raised and carried on through.
   *
   * @param result the result
   * @return the count, almost always zero
   */
  int resultNotices(long result);

  /**
   * One of those conditions.
   *
   * @param result the result
   * @param index the notice, counting from zero
   * @return the record, or null past the end
   */
  Diagnostic resultNotice(long result, int index);

  /**
   * Releases a result and everything borrowed from it.
   *
   * @param result the result, or zero
   */
  void resultFree(long result);

  // ---- columns ----

  /**
   * A whole column of integers, read where it lies.
   *
   * @param result the result
   * @param col the column, which must hold integers or booleans
   * @param rows how many values, which is {@link #resultRows(long)}
   * @return a read-only view of the engine's own memory, or null when the
   *     result has no rows
   */
  LongBuffer colLongs(long result, int col, long rows);

  /**
   * A whole column of floats, read where it lies.
   *
   * @param result the result
   * @param col the column, which must hold floats or integers
   * @param rows how many values
   * @return a read-only view, or null when the result has no rows
   */
  DoubleBuffer colDoubles(long result, int col, long rows);

  /**
   * A whole column of node row offsets, read where it lies.
   *
   * @param result the result
   * @param col the column, which must hold nodes
   * @param rows how many values
   * @return a read-only view, or null when the result has no rows
   */
  LongBuffer colNodeOffsets(long result, int col, long rows);

  /**
   * Which values of a column are not null, one byte a row.
   *
   * @param result the result
   * @param col the column
   * @param rows how many values
   * @return a read-only view, or null when the result has no rows
   */
  ByteBuffer colValid(long result, int col, long rows);

  // ---- chunks ----

  /**
   * How many chunks a result has, which is the loop bound.
   *
   * @param result the result
   * @return the count, 0 for a result with no rows
   */
  long chunkCount(long result);

  /**
   * Where a chunk starts and how long it is.
   *
   * @param result the result
   * @param chunk the chunk, counting from zero
   * @return two values, the row this chunk starts at and how many rows it
   *     holds, never null
   */
  long[] chunk(long result, long chunk);

  /**
   * One chunk of a column of integers.
   *
   * @param result the result
   * @param chunk the chunk
   * @param col the column
   * @param rows how many values the chunk holds
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  LongBuffer chunkLongs(long result, long chunk, int col, long rows);

  /**
   * One chunk of a column of floats.
   *
   * @param result the result
   * @param chunk the chunk
   * @param col the column
   * @param rows how many values the chunk holds
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  DoubleBuffer chunkDoubles(long result, long chunk, int col, long rows);

  /**
   * One chunk of a column of node row offsets.
   *
   * @param result the result
   * @param chunk the chunk
   * @param col the column
   * @param rows how many values the chunk holds
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  LongBuffer chunkNodeOffsets(long result, long chunk, int col, long rows);

  /**
   * One chunk of a column's validity.
   *
   * @param result the result
   * @param chunk the chunk
   * @param col the column
   * @param rows how many values the chunk holds
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  ByteBuffer chunkValid(long result, long chunk, int col, long rows);

  // ---- values ----

  /**
   * One cell, as a value that can be read as the type it is. Points into the
   * result's own rows and is nothing to free.
   *
   * @param result the result
   * @param row the row
   * @param col the column
   * @return the value handle
   */
  long resultCell(long result, long row, int col);

  /**
   * What a value holds.
   *
   * @param value the value
   * @return one of the {@code ZU_TYPE_} values
   */
  int valueType(long value);

  /**
   * A value as a boolean.
   *
   * @param value the value, which must be a boolean
   * @return the boolean
   */
  boolean valueBoolean(long value);

  /**
   * A value as an integer.
   *
   * @param value the value, which must be an integer
   * @return the integer
   */
  long valueLong(long value);

  /**
   * A value as a float.
   *
   * @param value the value, which must be a float
   * @return the float
   */
  double valueDouble(long value);

  /**
   * A value as a string.
   *
   * @param value the value, which must be a string
   * @return the string, never null
   */
  String valueString(long value);

  /**
   * A value as a temporal.
   *
   * @param value the value, which must be a temporal
   * @return three values, the {@code ZU_TEMPORAL_} kind, the count in the
   *     unit that kind implies, and minutes east of UTC
   */
  long[] valueTemporal(long value);

  /**
   * A value as a node, which is a table and a row of it, because neither
   * identifies a node on its own.
   *
   * @param value the value, which must be a node
   * @return two values, the table and the row offset
   */
  long[] valueNode(long value);

  /**
   * A value as a relationship.
   *
   * @param value the value, which must be a relationship
   * @return three values, the table, the row it starts at and the row it ends at
   */
  long[] valueRel(long value);

  /**
   * How many elements a list, a path or a record has.
   *
   * @param value the value
   * @return the count, 0 for anything that is not one of the three
   */
  long valueLength(long value);

  /**
   * One element of a list, a path or a record.
   *
   * @param value the value
   * @param index the element, counting from zero
   * @return the element's value handle
   */
  long valueAt(long value, long index);

  /**
   * What one field of a record is called. Fields are in name order and a name
   * appears once, which is what makes two records written in different orders
   * one value.
   *
   * @param value the value, which must be a record
   * @param index the field, counting from zero
   * @return the name, never null
   */
  String valueField(long value, long index);

  // ---- bulk load ----

  /**
   * Starts a load, which builds a database that does not exist yet.
   *
   * @param path the file to make, which must not exist
   * @return the loader handle
   */
  long loaderCreate(String path);

  /**
   * Names the one table this load builds and how many rows it holds.
   *
   * @param loader the loader
   * @param nodes what the node table is called
   * @param edges what the relationship table is called, which the engine wants
   *     even for a load that adds no edges at all
   * @param rows how many rows every column will carry
   */
  void loaderTable(long loader, String nodes, String edges, long rows);

  /**
   * Adds edges, as the row each starts at and the row it ends at. Appends, so
   * it may be called as often as the caller likes.
   *
   * @param loader the loader
   * @param from the starting row of each edge
   * @param to the ending row of each edge
   */
  void loaderEdges(long loader, IntBuffer from, IntBuffer to);

  /**
   * Adds a column of integers.
   *
   * <p>A direct buffer is read where it lies and nothing is copied on this side
   * of the boundary. A heap buffer is copied off-heap first, because a native
   * function cannot be handed a Java array without either a copy or a pause
   * long enough to matter on a column this size.
   *
   * @param loader the loader
   * @param name what the column is called
   * @param values the values, of which {@code remaining()} are read
   */
  void loaderColumnLongs(long loader, String name, LongBuffer values);

  /**
   * Adds a column of doubles.
   *
   * @param loader the loader
   * @param name what the column is called
   * @param values the values, of which {@code remaining()} are read
   */
  void loaderColumnDoubles(long loader, String name, DoubleBuffer values);

  /**
   * Adds a column of booleans, one {@code int} a row, where anything not zero
   * is true.
   *
   * @param loader the loader
   * @param name what the column is called
   * @param values the values, of which {@code remaining()} are read
   */
  void loaderColumnBooleans(long loader, String name, IntBuffer values);

  /**
   * Adds a column of strings. Every one is checked for UTF-8 by the engine as
   * it arrives rather than read back later as something no query could return.
   *
   * @param loader the loader
   * @param name what the column is called
   * @param values the values, none of which may be null
   */
  void loaderColumnStrings(long loader, String name, List<String> values);

  /**
   * Adds a column of dates, times, datetimes or durations, as one kind and the
   * count each row holds in the unit that kind implies.
   *
   * @param loader the loader
   * @param name what the column is called
   * @param kind which of the {@code ZU_TEMPORAL_} kinds every row is
   * @param values the counts, of which {@code remaining()} are read
   */
  void loaderColumnTemporal(long loader, String name, int kind, LongBuffer values);

  /**
   * Writes it all. The database is on disk when this returns.
   *
   * @param loader the loader
   */
  void loaderFinish(long loader);

  /**
   * Releases a loader. A loader freed before it finished wrote nothing.
   *
   * @param loader the loader
   */
  void loaderFree(long loader);

  // ---- appending ----

  /**
   * Opens an appender on a table that already exists.
   *
   * @param conn the connection to write through
   * @param table what the table is called
   * @return the appender handle
   */
  long appenderOpen(long conn, String table);

  /**
   * Appends one boolean to the row being written.
   *
   * @param appender the appender
   * @param value the value
   */
  void appendBoolean(long appender, boolean value);

  /**
   * Appends one integer to the row being written.
   *
   * @param appender the appender
   * @param value the value
   */
  void appendLong(long appender, long value);

  /**
   * Appends one double to the row being written.
   *
   * @param appender the appender
   * @param value the value
   */
  void appendDouble(long appender, double value);

  /**
   * Appends one string to the row being written.
   *
   * @param appender the appender
   * @param value the value
   */
  void appendString(long appender, String value);

  /**
   * Appends one run of bytes to the row being written.
   *
   * @param appender the appender
   * @param value the bytes, of which {@code remaining()} are read
   */
  void appendBytes(long appender, ByteBuffer value);

  /**
   * Appends one date, time, datetime or duration to the row being written.
   *
   * @param appender the appender
   * @param kind which of the {@code ZU_TEMPORAL_} kinds it is
   * @param count how many of the unit that kind implies
   */
  void appendTemporal(long appender, int kind, long count);

  /**
   * Ends the row being written, which is what makes it a row.
   *
   * @param appender the appender
   */
  void appendEndRow(long appender);

  /**
   * Writes what is buffered.
   *
   * @param appender the appender
   */
  void appenderFlush(long appender);

  /**
   * Rows ended and not yet written.
   *
   * @param appender the appender
   * @return the count
   */
  long appenderBuffered(long appender);

  /**
   * Rows written across every flush.
   *
   * @param appender the appender
   * @return the count
   */
  long appenderCommitted(long appender);

  /**
   * How many values a row carries.
   *
   * @param appender the appender
   * @return the count
   */
  int appenderColumns(long appender);

  /**
   * What one of those values is called.
   *
   * @param appender the appender
   * @param col the column, counting from zero
   * @return the name, or null out of range
   */
  String appenderColumnName(long appender, int col);

  /**
   * Throws away what is buffered. Rows an earlier flush wrote are written and
   * this does not reach them.
   *
   * @param appender the appender
   * @return how many rows were thrown away
   */
  long appenderDiscard(long appender);

  /**
   * Flushes what is left and spends the appender.
   *
   * @param appender the appender
   * @return how many rows it wrote in all
   */
  long appenderClose(long appender);

  /**
   * Releases an appender. Writes what is still buffered, and cannot say
   * whether that worked, which is what {@link #appenderClose(long)} is for.
   *
   * @param appender the appender
   */
  void appenderFree(long appender);
}
