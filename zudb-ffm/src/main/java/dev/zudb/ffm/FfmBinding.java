package dev.zudb.ffm;

import static dev.zudb.ffm.Scratch.A;
import static dev.zudb.ffm.Scratch.B;
import static dev.zudb.ffm.Scratch.C;
import static dev.zudb.ffm.Scratch.ERR;
import static dev.zudb.ffm.Scratch.LEN;
import static dev.zudb.ffm.Scratch.OUT;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.zudb.Diagnostic;
import dev.zudb.Status;
import dev.zudb.spi.ZuBinding;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The C ABI, called through the Foreign Function and Memory API.
 *
 * <p>Handles cross this class as {@code long} and become
 * {@link MemorySegment#ofAddress} on the way out. That is deliberate. A
 * provider that handed {@code MemorySegment} up to the API would put an FFM
 * type in a surface a Java 17 program has to name, and there would be no JNI
 * provider possible behind the same interface.
 *
 * <p>Nothing here allocates per call. The out-parameters and the encoded
 * strings come out of a per-thread {@link Scratch} block that is wound back at
 * the top of each call, so a bind in a loop costs the downcall and nothing
 * else.
 */
final class FfmBinding implements ZuBinding {

  private static final int ZU_OK = 0;
  private static final int ZU_DONE = 2;

  private final Abi abi;

  FfmBinding(Abi abi) {
    this.abi = abi;
  }

  @Override
  public String version() {
    try {
      MemorySegment s = (MemorySegment) abi.version.invokeExact();
      return cstring(s.address());
    } catch (Throwable t) {
      throw fail("zu_version", t);
    }
  }

  @Override
  public long databaseOpen(String path, long memoryLimit, long threads, boolean readOnly) {
    return openOrCreate(abi.databaseOpen, "zu_database_open", path, memoryLimit, threads, readOnly);
  }

  @Override
  public long databaseCreate(String path, long memoryLimit, long threads, boolean readOnly) {
    return openOrCreate(
        abi.databaseCreate, "zu_database_create", path, memoryLimit, threads, readOnly);
  }

  @Override
  public long databaseMemory(long memoryLimit, long threads, boolean readOnly) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    MemorySegment cfg = config(s, memoryLimit, threads, readOnly);
    clear(sl);
    try {
      int st =
          (int)
              abi.databaseMemory.invokeExact(cfg, sl.asSlice(OUT, 8), sl.asSlice(ERR, 8));
      check("zu_database_memory", st, sl);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail("zu_database_memory", t);
    }
  }

  @Override
  public boolean databaseIsMemory(long db) {
    try {
      return (int) abi.databaseIsMemory.invokeExact(ptr(db)) == ZU_OK;
    } catch (Throwable t) {
      throw fail("zu_database_is_memory", t);
    }
  }

  @Override
  public String databasePath(long db) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int) abi.databasePath.invokeExact(ptr(db), sl.asSlice(OUT, 8), sl.asSlice(LEN, 8));
      if (st == ZU_DONE) {
        return null;
      }
      check("zu_database_path", st, null);
      return utf8(sl.get(ADDRESS, OUT).address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_database_path", t);
    }
  }

  @Override
  public void databaseClose(long db) {
    try {
      abi.databaseClose.invokeExact(ptr(db));
    } catch (Throwable t) {
      throw fail("zu_database_close", t);
    }
  }

  @Override
  public long connect(long db) {
    return handle(abi.connect, "zu_connect", db);
  }

  @Override
  public long connDuplicate(long conn) {
    return handle(abi.connDuplicate, "zu_conn_duplicate", conn);
  }

  @Override
  public void connClose(long conn) {
    try {
      abi.connClose.invokeExact(ptr(conn));
    } catch (Throwable t) {
      throw fail("zu_conn_close", t);
    }
  }

  @Override
  public void connInterrupt(long conn) {
    try {
      check("zu_conn_interrupt", (int) abi.connInterrupt.invokeExact(ptr(conn)), null);
    } catch (Throwable t) {
      throw fail("zu_conn_interrupt", t);
    }
  }

  @Override
  public long connRowsRead(long conn) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.connRowsRead.invokeExact(ptr(conn), sl.asSlice(OUT, 8));
      check("zu_conn_rows_read", st, null);
      return sl.get(JAVA_LONG, OUT);
    } catch (Throwable t) {
      throw fail("zu_conn_rows_read", t);
    }
  }

  @Override
  public boolean connInTransaction(long conn) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.connInTransaction.invokeExact(ptr(conn), sl.asSlice(OUT, 8));
      check("zu_conn_in_transaction", st, null);
      return sl.get(JAVA_INT, OUT) != 0;
    } catch (Throwable t) {
      throw fail("zu_conn_in_transaction", t);
    }
  }

  @Override
  public void begin(long conn, boolean readOnly) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    clear(sl);
    try {
      int st = (int) abi.begin.invokeExact(ptr(conn), readOnly ? 1 : 0, sl.asSlice(ERR, 8));
      check("zu_begin", st, sl);
    } catch (Throwable t) {
      throw fail("zu_begin", t);
    }
  }

  @Override
  public void commit(long conn) {
    endTransaction(abi.commit, "zu_commit", conn);
  }

  @Override
  public void rollback(long conn) {
    endTransaction(abi.rollback, "zu_rollback", conn);
  }

  @Override
  public long query(long conn, String statement) {
    return run(abi.query, "zu_query", conn, statement);
  }

  @Override
  public long prepare(long conn, String statement) {
    return run(abi.prepare, "zu_prepare", conn, statement);
  }

  @Override
  public void bindLong(long stmt, String name, long value) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    try {
      int st = (int) abi.bindI64.invokeExact(ptr(stmt), n, n.byteSize(), value);
      check("zu_bind_i64", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_i64", t);
    }
  }

  @Override
  public void bindDouble(long stmt, String name, double value) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    try {
      int st = (int) abi.bindF64.invokeExact(ptr(stmt), n, n.byteSize(), value);
      check("zu_bind_f64", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_f64", t);
    }
  }

  @Override
  public void bindBoolean(long stmt, String name, boolean value) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    try {
      int st = (int) abi.bindBool.invokeExact(ptr(stmt), n, n.byteSize(), value ? 1 : 0);
      check("zu_bind_bool", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_bool", t);
    }
  }

  @Override
  public void bindString(long stmt, String name, String value) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    MemorySegment v = s.utf8(value);
    try {
      int st = (int) abi.bindStr.invokeExact(ptr(stmt), n, n.byteSize(), v, v.byteSize());
      check("zu_bind_str", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_str", t);
    }
  }

  @Override
  public void bindTemporal(long stmt, String name, int kind, long count, int offsetMinutes) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    try {
      int st =
          (int)
              abi.bindTemporal.invokeExact(ptr(stmt), n, n.byteSize(), kind, count, offsetMinutes);
      check("zu_bind_temporal", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_temporal", t);
    }
  }

  @Override
  public void bindNull(long stmt, String name) {
    Scratch s = Scratch.get();
    MemorySegment n = s.utf8(name);
    try {
      int st = (int) abi.bindNull.invokeExact(ptr(stmt), n, n.byteSize());
      check("zu_bind_null", st, null);
    } catch (Throwable t) {
      throw fail("zu_bind_null", t);
    }
  }

  @Override
  public long execute(long stmt) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    clear(sl);
    try {
      int st = (int) abi.execute.invokeExact(ptr(stmt), sl.asSlice(OUT, 8), sl.asSlice(ERR, 8));
      check("zu_execute", st, sl);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail("zu_execute", t);
    }
  }

  @Override
  public void stmtClose(long stmt) {
    try {
      abi.stmtClose.invokeExact(ptr(stmt));
    } catch (Throwable t) {
      throw fail("zu_stmt_close", t);
    }
  }

  @Override
  public long resultRows(long result) {
    try {
      return (long) abi.resultRows.invokeExact(ptr(result));
    } catch (Throwable t) {
      throw fail("zu_result_rows", t);
    }
  }

  @Override
  public int resultCols(long result) {
    try {
      return (int) abi.resultCols.invokeExact(ptr(result));
    } catch (Throwable t) {
      throw fail("zu_result_cols", t);
    }
  }

  @Override
  public String resultColName(long result, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int)
              abi.resultColName.invokeExact(ptr(result), col, sl.asSlice(OUT, 8), sl.asSlice(LEN, 8));
      check("zu_result_col_name", st, null);
      return utf8(sl.get(ADDRESS, OUT).address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_result_col_name", t);
    }
  }

  @Override
  public int resultCellType(long result, long row, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.resultCellType.invokeExact(ptr(result), row, col, sl.asSlice(OUT, 8));
      check("zu_result_cell_type", st, null);
      return sl.get(JAVA_INT, OUT);
    } catch (Throwable t) {
      throw fail("zu_result_cell_type", t);
    }
  }

  @Override
  public String resultCellString(long result, long row, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int)
              abi.resultCellStr.invokeExact(
                  ptr(result), row, col, sl.asSlice(OUT, 8), sl.asSlice(LEN, 8));
      check("zu_result_cell_str", st, null);
      return utf8(sl.get(ADDRESS, OUT).address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_result_cell_str", t);
    }
  }

  @Override
  public String resultGqlstatus(long result) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      MemorySegment out =
          (MemorySegment) abi.resultGqlstatus.invokeExact(ptr(result), sl.asSlice(LEN, 8));
      return utf8(out.address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_result_gqlstatus", t);
    }
  }

  @Override
  public int resultNotices(long result) {
    try {
      return (int) abi.resultNotices.invokeExact(ptr(result));
    } catch (Throwable t) {
      throw fail("zu_result_notices", t);
    }
  }

  @Override
  public Diagnostic resultNotice(long result, int index) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    clear(sl);
    try {
      int st = (int) abi.resultNotice.invokeExact(ptr(result), index, sl.asSlice(ERR, 8));
      if (st == ZU_DONE) {
        return null;
      }
      check("zu_result_notice", st, null);
      long err = sl.get(ADDRESS, ERR).address();
      return err == 0 ? null : diagnostic(err);
    } catch (Throwable t) {
      throw fail("zu_result_notice", t);
    }
  }

  @Override
  public void resultFree(long result) {
    try {
      abi.resultFree.invokeExact(ptr(result));
    } catch (Throwable t) {
      throw fail("zu_result_free", t);
    }
  }

  @Override
  public LongBuffer colLongs(long result, int col, long rows) {
    long p = column(abi.colI64, "zu_result_col_i64", result, col);
    return p == 0 ? null : buffer(p, rows, 8).asLongBuffer();
  }

  @Override
  public DoubleBuffer colDoubles(long result, int col, long rows) {
    long p = column(abi.colF64, "zu_result_col_f64", result, col);
    return p == 0 ? null : buffer(p, rows, 8).asDoubleBuffer();
  }

  @Override
  public LongBuffer colNodeOffsets(long result, int col, long rows) {
    long p = column(abi.colNodeOffset, "zu_result_col_node_offset", result, col);
    return p == 0 ? null : buffer(p, rows, 8).asLongBuffer();
  }

  @Override
  public ByteBuffer colValid(long result, int col, long rows) {
    long p = column(abi.colValid, "zu_result_col_valid", result, col);
    return p == 0 ? null : buffer(p, rows, 1);
  }

  @Override
  public long chunkCount(long result) {
    try {
      return (long) abi.chunkCount.invokeExact(ptr(result));
    } catch (Throwable t) {
      throw fail("zu_result_chunk_count", t);
    }
  }

  @Override
  public long[] chunk(long result, long chunk) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int) abi.chunk.invokeExact(ptr(result), chunk, sl.asSlice(OUT, 8), sl.asSlice(A, 8));
      check("zu_result_chunk", st, null);
      return new long[] {sl.get(JAVA_LONG, OUT), sl.get(JAVA_LONG, A)};
    } catch (Throwable t) {
      throw fail("zu_result_chunk", t);
    }
  }

  @Override
  public LongBuffer chunkLongs(long result, long chunk, int col, long rows) {
    long p = chunkColumn(abi.chunkColI64, "zu_result_chunk_col_i64", result, chunk, col);
    return p == 0 ? null : buffer(p, rows, 8).asLongBuffer();
  }

  @Override
  public DoubleBuffer chunkDoubles(long result, long chunk, int col, long rows) {
    long p = chunkColumn(abi.chunkColF64, "zu_result_chunk_col_f64", result, chunk, col);
    return p == 0 ? null : buffer(p, rows, 8).asDoubleBuffer();
  }

  @Override
  public LongBuffer chunkNodeOffsets(long result, long chunk, int col, long rows) {
    long p =
        chunkColumn(abi.chunkColNodeOffset, "zu_result_chunk_col_node_offset", result, chunk, col);
    return p == 0 ? null : buffer(p, rows, 8).asLongBuffer();
  }

  @Override
  public ByteBuffer chunkValid(long result, long chunk, int col, long rows) {
    long p = chunkColumn(abi.chunkColValid, "zu_result_chunk_col_valid", result, chunk, col);
    return p == 0 ? null : buffer(p, rows, 1);
  }

  @Override
  public long resultCell(long result, long row, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.resultCell.invokeExact(ptr(result), row, col, sl.asSlice(OUT, 8));
      check("zu_result_cell", st, null);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail("zu_result_cell", t);
    }
  }

  @Override
  public int valueType(long value) {
    try {
      return (int) abi.valueType.invokeExact(ptr(value));
    } catch (Throwable t) {
      throw fail("zu_value_type", t);
    }
  }

  @Override
  public boolean valueBoolean(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueBool.invokeExact(ptr(value), sl.asSlice(OUT, 8));
      check("zu_value_bool", st, null);
      return sl.get(JAVA_INT, OUT) != 0;
    } catch (Throwable t) {
      throw fail("zu_value_bool", t);
    }
  }

  @Override
  public long valueLong(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueI64.invokeExact(ptr(value), sl.asSlice(OUT, 8));
      check("zu_value_i64", st, null);
      return sl.get(JAVA_LONG, OUT);
    } catch (Throwable t) {
      throw fail("zu_value_i64", t);
    }
  }

  @Override
  public double valueDouble(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueF64.invokeExact(ptr(value), sl.asSlice(OUT, 8));
      check("zu_value_f64", st, null);
      return sl.get(JAVA_DOUBLE, OUT);
    } catch (Throwable t) {
      throw fail("zu_value_f64", t);
    }
  }

  @Override
  public String valueString(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueStr.invokeExact(ptr(value), sl.asSlice(OUT, 8), sl.asSlice(LEN, 8));
      check("zu_value_str", st, null);
      return utf8(sl.get(ADDRESS, OUT).address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_value_str", t);
    }
  }

  @Override
  public long[] valueTemporal(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int)
              abi.valueTemporal.invokeExact(
                  ptr(value), sl.asSlice(A, 8), sl.asSlice(OUT, 8), sl.asSlice(B, 8));
      check("zu_value_temporal", st, null);
      return new long[] {sl.get(JAVA_INT, A), sl.get(JAVA_LONG, OUT), sl.get(JAVA_INT, B)};
    } catch (Throwable t) {
      throw fail("zu_value_temporal", t);
    }
  }

  @Override
  public long[] valueNode(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueNode.invokeExact(ptr(value), sl.asSlice(A, 8), sl.asSlice(OUT, 8));
      check("zu_value_node", st, null);
      return new long[] {Integer.toUnsignedLong(sl.get(JAVA_INT, A)), sl.get(JAVA_LONG, OUT)};
    } catch (Throwable t) {
      throw fail("zu_value_node", t);
    }
  }

  @Override
  public long[] valueRel(long value) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int)
              abi.valueRel.invokeExact(
                  ptr(value), sl.asSlice(A, 8), sl.asSlice(OUT, 8), sl.asSlice(C, 8));
      check("zu_value_rel", st, null);
      return new long[] {
        Integer.toUnsignedLong(sl.get(JAVA_INT, A)), sl.get(JAVA_LONG, OUT), sl.get(JAVA_LONG, C)
      };
    } catch (Throwable t) {
      throw fail("zu_value_rel", t);
    }
  }

  @Override
  public long valueLength(long value) {
    try {
      return (long) abi.valueLen.invokeExact(ptr(value));
    } catch (Throwable t) {
      throw fail("zu_value_len", t);
    }
  }

  @Override
  public long valueAt(long value, long index) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) abi.valueAt.invokeExact(ptr(value), index, sl.asSlice(OUT, 8));
      check("zu_value_at", st, null);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail("zu_value_at", t);
    }
  }

  @Override
  public String valueField(long value, long index) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st =
          (int) abi.valueField.invokeExact(ptr(value), index, sl.asSlice(OUT, 8), sl.asSlice(LEN, 8));
      check("zu_value_field", st, null);
      return utf8(sl.get(ADDRESS, OUT).address(), sl.get(JAVA_LONG, LEN));
    } catch (Throwable t) {
      throw fail("zu_value_field", t);
    }
  }

  private long openOrCreate(
      java.lang.invoke.MethodHandle mh,
      String what,
      String path,
      long memoryLimit,
      long threads,
      boolean readOnly) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    MemorySegment p = s.utf8(path);
    MemorySegment cfg = config(s, memoryLimit, threads, readOnly);
    clear(sl);
    try {
      int st = (int) mh.invokeExact(p, p.byteSize(), cfg, sl.asSlice(OUT, 8), sl.asSlice(ERR, 8));
      check(what, st, sl);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private long handle(java.lang.invoke.MethodHandle mh, String what, long in) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    clear(sl);
    try {
      int st = (int) mh.invokeExact(ptr(in), sl.asSlice(OUT, 8), sl.asSlice(ERR, 8));
      check(what, st, sl);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private long run(java.lang.invoke.MethodHandle mh, String what, long conn, String statement) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    MemorySegment q = s.utf8(statement);
    clear(sl);
    try {
      int st =
          (int) mh.invokeExact(ptr(conn), q, q.byteSize(), sl.asSlice(OUT, 8), sl.asSlice(ERR, 8));
      check(what, st, sl);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private void endTransaction(java.lang.invoke.MethodHandle mh, String what, long conn) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    clear(sl);
    try {
      int st = (int) mh.invokeExact(ptr(conn), sl.asSlice(ERR, 8));
      check(what, st, sl);
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private long column(java.lang.invoke.MethodHandle mh, String what, long result, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) mh.invokeExact(ptr(result), col, sl.asSlice(OUT, 8));
      if (st == ZU_DONE) {
        return 0;
      }
      check(what, st, null);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private long chunkColumn(
      java.lang.invoke.MethodHandle mh, String what, long result, long chunk, int col) {
    Scratch s = Scratch.get();
    MemorySegment sl = s.slots();
    try {
      int st = (int) mh.invokeExact(ptr(result), chunk, col, sl.asSlice(OUT, 8));
      if (st == ZU_DONE) {
        return 0;
      }
      check(what, st, null);
      return sl.get(ADDRESS, OUT).address();
    } catch (Throwable t) {
      throw fail(what, t);
    }
  }

  private static MemorySegment config(Scratch s, long memoryLimit, long threads, boolean readOnly) {
    MemorySegment cfg = s.config();
    cfg.set(JAVA_LONG, 0, Scratch.CONFIG);
    cfg.set(JAVA_LONG, 8, memoryLimit);
    cfg.set(JAVA_LONG, 16, threads);
    cfg.set(JAVA_INT, 24, readOnly ? 1 : 0);
    return cfg;
  }

  private static MemorySegment ptr(long handle) {
    return handle == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(handle);
  }

  private static void clear(MemorySegment slots) {
    slots.set(ADDRESS, ERR, MemorySegment.NULL);
  }

  /**
   * Turns a status that is not {@code ZU_OK} into the exception it names.
   *
   * @param what the C function, for the message a status with no error carries
   * @param status what it answered
   * @param slots the block whose error slot the call may have written, or null
   *     for a call that takes no error out-parameter
   */
  private void check(String what, int status, MemorySegment slots) {
    if (status == ZU_OK) {
      return;
    }
    long err = slots == null ? 0 : slots.get(ADDRESS, ERR).address();
    if (err != 0) {
      throw diagnostic(err).toException();
    }
    throw Diagnostic.misuse(Status.of(status), what + " answered " + Status.of(status))
        .toException();
  }

  /** Reads a {@code zu_error} into a record and frees it. */
  private Diagnostic diagnostic(long err) {
    MemorySegment e = ptr(err);
    try {
      int status = (int) abi.errorStatus.invokeExact(e);
      int severity = (int) abi.errorSeverity.invokeExact(e);
      int retryable = (int) abi.errorRetryable.invokeExact(e);
      String message = text(abi.errorMessage, e);
      String code = text(abi.errorCode, e);
      String condition = text(abi.errorStandardText, e);
      String docUrl = text(abi.errorDocUrl, e);
      String excerpt = text(abi.errorExcerpt, e);
      int line = -1;
      int column = -1;
      int offset = -1;
      MemorySegment sl = Scratch.get().slots();
      if ((int) abi.errorPosition.invokeExact(e, sl.asSlice(A, 4), sl.asSlice(B, 4)) == ZU_OK) {
        line = sl.get(JAVA_INT, A);
        column = sl.get(JAVA_INT, B);
      }
      if ((int) abi.errorOffset.invokeExact(e, sl.asSlice(C, 4)) == ZU_OK) {
        offset = sl.get(JAVA_INT, C);
      }
      return Diagnostic.of(
          status, message, code, condition, severity, line, column, offset, excerpt, docUrl,
          retryable == 1);
    } catch (Throwable t) {
      throw fail("zu_error", t);
    } finally {
      try {
        abi.errorFree.invokeExact(e);
      } catch (Throwable t) {
        throw fail("zu_error_free", t);
      }
    }
  }

  /** One of the {@code const char *} accessors on a {@code zu_error}. */
  private static String text(java.lang.invoke.MethodHandle mh, MemorySegment e) throws Throwable {
    MemorySegment sl = Scratch.get().slots();
    MemorySegment out = (MemorySegment) mh.invokeExact(e, sl.asSlice(LEN, 8));
    return utf8(out.address(), sl.get(JAVA_LONG, LEN));
  }

  private static String utf8(long address, long length) {
    if (address == 0) {
      return null;
    }
    if (length == 0) {
      return "";
    }
    byte[] bytes = new byte[(int) length];
    MemorySegment.copy(reinterpret(address, length), JAVA_BYTE, 0, bytes, 0, bytes.length);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("restricted")
  private static String cstring(long address) {
    return address == 0 ? null : MemorySegment.ofAddress(address).reinterpret(Long.MAX_VALUE).getString(0);
  }

  @SuppressWarnings("restricted")
  private static MemorySegment reinterpret(long address, long size) {
    return MemorySegment.ofAddress(address).reinterpret(size);
  }

  /** A native array of {@code count} items of {@code width} bytes, as a buffer over it. */
  private static ByteBuffer buffer(long address, long count, long width) {
    long size = count * width;
    if (size > Integer.MAX_VALUE) {
      throw Diagnostic.misuse(
              Status.UNSUPPORTED,
              "this column is "
                  + size
                  + " bytes, and a java.nio buffer addresses at most "
                  + Integer.MAX_VALUE
                  + ": read it a chunk at a time")
          .toException();
    }
    return reinterpret(address, size)
        .asByteBuffer()
        .asReadOnlyBuffer()
        .order(ByteOrder.nativeOrder());
  }

  private static RuntimeException fail(String what, Throwable t) {
    if (t instanceof Error e) {
      throw e;
    }
    if (t instanceof RuntimeException e) {
      return e;
    }
    return Diagnostic.misuse(Status.UNKNOWN, what + " did not complete: " + t).toException();
  }
}
