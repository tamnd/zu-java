package dev.zudb.jni;

import dev.zudb.Diagnostic;
import dev.zudb.Progress;
import dev.zudb.Status;
import dev.zudb.ZuException;
import dev.zudb.spi.ZuBinding;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The C ABI, called through JNI.
 *
 * <p>This is the provider for the JDKs that have no Panama: 17 through 21,
 * which is still most of what is deployed. It costs a small native shim, one
 * per platform, that the artifact carries and unpacks; everything it does is
 * the same ABI with the same handles and the same rules, so a program that
 * runs on one provider runs on the other without knowing which it got.
 *
 * <h2>Strings</h2>
 *
 * <p>Every string crosses as a byte array rather than as a {@code String}.
 * JNI's own conversions speak modified UTF-8, which spells a character outside
 * the basic multilingual plane as a surrogate pair in six bytes and a NUL in
 * two. The engine validates real UTF-8, so the first emoji anybody stored
 * would be refused as a bad encoding and the first one anybody read back would
 * come out mangled. Encoding here costs an array per call on the string paths,
 * which is the price of being right, and no column path touches it.
 *
 * <h2>Buffers</h2>
 *
 * <p>A column comes back as a direct buffer over the engine's own memory,
 * made by {@code NewDirectByteBuffer}, so reading a column of a million
 * integers allocates nothing here either. A buffer handed the other way has to
 * have an address, so one on the heap is copied into a direct buffer for the
 * calls that read and are done, and refused for a frame, which keeps the
 * pointer.
 */
final class JniBinding implements ZuBinding {

  /**
   * The watch cookie each connection is watching through, which is the one
   * thing this binding has to remember about a connection.
   *
   * <p>It is the address of a cell in the shim holding a global reference to
   * the watcher. Keyed by the handle, which is safe because the entry goes
   * when the connection closes and not a moment later, so an address the
   * allocator hands out again cannot find an old one.
   */
  private final Map<Long, Long> watches = new ConcurrentHashMap<>();

  JniBinding() {}

  // ---- strings ----

  private static byte[] u8(String s) {
    return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
  }

  private static String str(byte[] b) {
    return b == null ? null : new String(b, StandardCharsets.UTF_8);
  }

  // ---- buffers ----

  /**
   * A window onto a run of the engine's memory, as the buffer the API module
   * hands a caller.
   *
   * <p>The size is checked here rather than in the shim so that a column too
   * big to address says what to do about it. A {@code java.nio} buffer counts
   * in {@code int}, which runs out at two gigabytes, and the chunked accessors
   * are exactly the way round that.
   */
  private static ByteBuffer window(ByteBuffer raw) {
    return raw == null ? null : raw.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
  }

  private static void addressable(long rows, long width, String what) {
    long size = rows * width;
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
    if (rows < 0) {
      throw Diagnostic.misuse(Status.MISUSE, what + " was asked for " + rows + " rows")
          .toException();
    }
  }

  /** How many bytes of a buffer are between its position and its limit. */
  private static long byteSize(Buffer b) {
    int width;
    if (b instanceof ByteBuffer) {
      width = 1;
    } else if (b instanceof ShortBuffer || b instanceof CharBuffer) {
      width = 2;
    } else if (b instanceof IntBuffer || b instanceof FloatBuffer) {
      width = 4;
    } else {
      width = 8;
    }
    return (long) b.remaining() * width;
  }

  /**
   * A buffer the engine may keep rather than read once.
   *
   * <p>This is the one place a copy is refused instead of made. Everywhere
   * else a heap buffer costs a memcpy and nothing else, because the call reads
   * it and is done. A frame keeps the pointer for as long as it is registered,
   * and a heap buffer has no address anything outside the JVM can keep, so a
   * copy here would mean the engine reading a copy for the rest of the frame's
   * life. That is a frame that is not a frame, and quietly making one is worse
   * than saying so.
   */
  private static Buffer lent(Buffer buffer, String name) {
    if (buffer == null) {
      throw Diagnostic.misuse(Status.MISUSE, "column " + name + " of a frame has no buffer")
          .toException();
    }
    if (!buffer.isDirect()) {
      throw Diagnostic.misuse(
              Status.MISUSE,
              "column "
                  + name
                  + " of a frame is on the heap, and a frame is read where it lies rather than"
                  + " copied, so it wants a buffer from ByteBuffer.allocateDirect")
          .toException();
    }
    // The shim asks the JVM for the address of a buffer, and that answer
    // is the start of the buffer rather than of what is left in it. A
    // slice is the same memory from the position on, so the two agree.
    return buffer.position() == 0 ? buffer : buffer.slice();
  }

  /** The same buffer if it is direct, and a direct copy of it if it is not. */
  private static LongBuffer pass(LongBuffer values) {
    if (values.isDirect()) {
      return values.position() == 0 ? values : values.slice();
    }
    LongBuffer copy =
        ByteBuffer.allocateDirect(values.remaining() * 8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer();
    copy.put(values.duplicate());
    copy.flip();
    return copy;
  }

  private static DoubleBuffer pass(DoubleBuffer values) {
    if (values.isDirect()) {
      return values.position() == 0 ? values : values.slice();
    }
    DoubleBuffer copy =
        ByteBuffer.allocateDirect(values.remaining() * 8)
            .order(ByteOrder.nativeOrder())
            .asDoubleBuffer();
    copy.put(values.duplicate());
    copy.flip();
    return copy;
  }

  private static IntBuffer pass(IntBuffer values) {
    if (values.isDirect()) {
      return values.position() == 0 ? values : values.slice();
    }
    IntBuffer copy =
        ByteBuffer.allocateDirect(values.remaining() * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
    copy.put(values.duplicate());
    copy.flip();
    return copy;
  }

  private static ByteBuffer pass(ByteBuffer values) {
    if (values.isDirect()) {
      return values.position() == 0 ? values : values.slice();
    }
    ByteBuffer copy = ByteBuffer.allocateDirect(values.remaining()).order(ByteOrder.nativeOrder());
    copy.put(values.duplicate());
    copy.flip();
    return copy;
  }

  // ---- what the shim calls back ----

  /**
   * A diagnostic, built here because the mapping from the ABI's numbers to
   * this client's two enums belongs in one place and that place is the API
   * module.
   *
   * <p>Called from the shim and from nowhere else, which is why it is private
   * and why nothing in this file appears to use it.
   */
  private static Diagnostic diagnostic(
      int status,
      byte[] message,
      byte[] code,
      byte[] condition,
      int severity,
      int line,
      int column,
      int offset,
      byte[] excerpt,
      byte[] docUrl,
      boolean retryable) {
    return Diagnostic.of(
        status,
        str(message),
        str(code),
        str(condition),
        severity,
        line,
        column,
        offset,
        str(excerpt),
        str(docUrl),
        retryable);
  }

  /** The exception a status that carries no error of its own is. */
  private static ZuException misuse(int status, String what) {
    return Diagnostic.misuse(Status.of(status), what + " answered " + Status.of(status))
        .toException();
  }

  // ---- the interface ----

  @Override
  public String version() {
    return str(nVersion());
  }

  @Override
  public long[] configSet(
      long memoryLimit, long threads, boolean readOnly, String key, String value) {
    return nConfigSet(memoryLimit, threads, readOnly, u8(key), u8(value));
  }

  @Override
  public long databaseOpen(String path, long memoryLimit, long threads, boolean readOnly) {
    return nDatabaseOpen(u8(path), memoryLimit, threads, readOnly);
  }

  @Override
  public long databaseCreate(String path, long memoryLimit, long threads, boolean readOnly) {
    return nDatabaseCreate(u8(path), memoryLimit, threads, readOnly);
  }

  @Override
  public long databaseMemory(long memoryLimit, long threads, boolean readOnly) {
    return nDatabaseMemory(memoryLimit, threads, readOnly);
  }

  @Override
  public boolean databaseIsMemory(long db) {
    return nDatabaseIsMemory(db);
  }

  @Override
  public String databasePath(long db) {
    return str(nDatabasePath(db));
  }

  @Override
  public void databaseClose(long db) {
    nDatabaseClose(db);
  }

  @Override
  public long connect(long db) {
    return nConnect(db);
  }

  @Override
  public long open(String path) {
    return nOpen(u8(path));
  }

  @Override
  public long create(String path) {
    return nCreate(u8(path));
  }

  @Override
  public long memory() {
    return nMemory();
  }

  @Override
  public long connDuplicate(long conn) {
    return nConnDuplicate(conn);
  }

  @Override
  public void connClose(long conn) {
    nConnClose(conn);
    spend(watches.remove(conn));
  }

  @Override
  public void connInterrupt(long conn) {
    nConnInterrupt(conn);
  }

  @Override
  public long connRowsRead(long conn) {
    return nConnRowsRead(conn);
  }

  @Override
  public String connTableName(long conn, int table) {
    return str(nConnTableName(conn, table));
  }

  @Override
  public void connSetProgress(long conn, Progress watcher, long intervalMillis) {
    long cookie = nConnSetProgress(conn, watcher, intervalMillis);
    // The old arrangement goes only once the engine has the new one, so
    // there is no moment at which the reference the engine holds is one
    // this side has already given back.
    Long before = cookie == 0 ? watches.remove(conn) : watches.put(conn, cookie);
    spend(before);
  }

  private static void spend(Long cookie) {
    if (cookie != null && cookie != 0) {
      nWatchFree(cookie);
    }
  }

  @Override
  public boolean connInTransaction(long conn) {
    return nConnInTransaction(conn);
  }

  @Override
  public void begin(long conn, boolean readOnly) {
    nBegin(conn, readOnly);
  }

  @Override
  public void commit(long conn) {
    nCommit(conn);
  }

  @Override
  public void rollback(long conn) {
    nRollback(conn);
  }

  @Override
  public long query(long conn, String statement) {
    return nQuery(conn, u8(statement));
  }

  @Override
  public long prepare(long conn, String statement) {
    return nPrepare(conn, u8(statement));
  }

  @Override
  public void bindLong(long stmt, String name, long value) {
    nBindLong(stmt, u8(name), value);
  }

  @Override
  public void bindDouble(long stmt, String name, double value) {
    nBindDouble(stmt, u8(name), value);
  }

  @Override
  public void bindBoolean(long stmt, String name, boolean value) {
    nBindBoolean(stmt, u8(name), value);
  }

  @Override
  public void bindString(long stmt, String name, String value) {
    nBindString(stmt, u8(name), u8(value));
  }

  @Override
  public void bindTemporal(long stmt, String name, int kind, long count, int offsetMinutes) {
    nBindTemporal(stmt, u8(name), kind, count, offsetMinutes);
  }

  @Override
  public void bindNull(long stmt, String name) {
    nBindNull(stmt, u8(name));
  }

  @Override
  public long execute(long stmt) {
    return nExecute(stmt);
  }

  @Override
  public void stmtClose(long stmt) {
    nStmtClose(stmt);
  }

  @Override
  public long resultRows(long result) {
    return nResultRows(result);
  }

  @Override
  public int resultCols(long result) {
    return nResultCols(result);
  }

  @Override
  public String resultColName(long result, int col) {
    return str(nResultColName(result, col));
  }

  @Override
  public int resultCellType(long result, long row, int col) {
    return nResultCellType(result, row, col);
  }

  @Override
  public String resultCellString(long result, long row, int col) {
    return str(nResultCellString(result, row, col));
  }

  @Override
  public String resultGqlstatus(long result) {
    return str(nResultGqlstatus(result));
  }

  @Override
  public int resultNotices(long result) {
    return nResultNotices(result);
  }

  @Override
  public Diagnostic resultNotice(long result, int index) {
    return nResultNotice(result, index);
  }

  @Override
  public void resultFree(long result) {
    nResultFree(result);
  }

  @Override
  public LongBuffer colLongs(long result, int col, long rows) {
    addressable(rows, 8, "zu_result_col_i64");
    ByteBuffer raw = window(nColLongs(result, col, rows));
    return raw == null ? null : raw.asLongBuffer();
  }

  @Override
  public DoubleBuffer colDoubles(long result, int col, long rows) {
    addressable(rows, 8, "zu_result_col_f64");
    ByteBuffer raw = window(nColDoubles(result, col, rows));
    return raw == null ? null : raw.asDoubleBuffer();
  }

  @Override
  public LongBuffer colNodeOffsets(long result, int col, long rows) {
    addressable(rows, 8, "zu_result_col_node_offset");
    ByteBuffer raw = window(nColNodeOffsets(result, col, rows));
    return raw == null ? null : raw.asLongBuffer();
  }

  @Override
  public ByteBuffer colValid(long result, int col, long rows) {
    addressable(rows, 1, "zu_result_col_valid");
    return window(nColValid(result, col, rows));
  }

  @Override
  public long chunkCount(long result) {
    return nChunkCount(result);
  }

  @Override
  public long[] chunk(long result, long chunk) {
    return nChunk(result, chunk);
  }

  @Override
  public LongBuffer chunkLongs(long result, long chunk, int col, long rows) {
    addressable(rows, 8, "zu_result_chunk_col_i64");
    ByteBuffer raw = window(nChunkLongs(result, chunk, col, rows));
    return raw == null ? null : raw.asLongBuffer();
  }

  @Override
  public DoubleBuffer chunkDoubles(long result, long chunk, int col, long rows) {
    addressable(rows, 8, "zu_result_chunk_col_f64");
    ByteBuffer raw = window(nChunkDoubles(result, chunk, col, rows));
    return raw == null ? null : raw.asDoubleBuffer();
  }

  @Override
  public LongBuffer chunkNodeOffsets(long result, long chunk, int col, long rows) {
    addressable(rows, 8, "zu_result_chunk_col_node_offset");
    ByteBuffer raw = window(nChunkNodeOffsets(result, chunk, col, rows));
    return raw == null ? null : raw.asLongBuffer();
  }

  @Override
  public ByteBuffer chunkValid(long result, long chunk, int col, long rows) {
    addressable(rows, 1, "zu_result_chunk_col_valid");
    return window(nChunkValid(result, chunk, col, rows));
  }

  @Override
  public void resultArrow(long conn, long result, long rowsPerBatch, long stream) {
    nResultArrow(conn, result, rowsPerBatch, stream);
  }

  @Override
  public long resultCell(long result, long row, int col) {
    return nResultCell(result, row, col);
  }

  @Override
  public int valueType(long value) {
    return nValueType(value);
  }

  @Override
  public boolean valueBoolean(long value) {
    return nValueBoolean(value);
  }

  @Override
  public long valueLong(long value) {
    return nValueLong(value);
  }

  @Override
  public double valueDouble(long value) {
    return nValueDouble(value);
  }

  @Override
  public String valueString(long value) {
    return str(nValueString(value));
  }

  @Override
  public byte[] valueBytes(long value) {
    byte[] octets = nValueBytes(value);
    return octets == null ? new byte[0] : octets;
  }

  @Override
  public long[] valueTemporal(long value) {
    return nValueTemporal(value);
  }

  @Override
  public long[] valueNode(long value) {
    return nValueNode(value);
  }

  @Override
  public long[] valueRel(long value) {
    return nValueRel(value);
  }

  @Override
  public long valueLength(long value) {
    return nValueLength(value);
  }

  @Override
  public long valueAt(long value, long index) {
    return nValueAt(value, index);
  }

  @Override
  public String valueField(long value, long index) {
    return str(nValueField(value, index));
  }

  @Override
  public long loaderCreate(String path) {
    return nLoaderCreate(u8(path));
  }

  @Override
  public void loaderTable(long loader, String nodes, String edges, long rows) {
    nLoaderTable(loader, u8(nodes), u8(edges), rows);
  }

  @Override
  public void loaderEdges(long loader, IntBuffer from, IntBuffer to) {
    int count = from.remaining();
    if (to.remaining() != count) {
      throw Diagnostic.misuse(
              Status.MISUSE,
              "an edge starts somewhere and ends somewhere, and there are "
                  + count
                  + " starts against "
                  + to.remaining()
                  + " ends")
          .toException();
    }
    nLoaderEdges(loader, pass(from), pass(to), count);
  }

  @Override
  public void loaderColumnLongs(long loader, String name, LongBuffer values) {
    nLoaderColLongs(loader, u8(name), pass(values), values.remaining());
  }

  @Override
  public void loaderColumnDoubles(long loader, String name, DoubleBuffer values) {
    nLoaderColDoubles(loader, u8(name), pass(values), values.remaining());
  }

  @Override
  public void loaderColumnBooleans(long loader, String name, IntBuffer values) {
    nLoaderColBooleans(loader, u8(name), pass(values), values.remaining());
  }

  @Override
  public void loaderColumnStrings(long loader, String name, List<String> values) {
    byte[][] encoded = new byte[values.size()][];
    for (int i = 0; i < encoded.length; i++) {
      String v = values.get(i);
      if (v == null) {
        throw Diagnostic.misuse(
                Status.MISUSE,
                "row "
                    + i
                    + " of column "
                    + name
                    + " is no value at all, and a loaded column holds a value a row")
            .toException();
      }
      encoded[i] = u8(v);
    }
    nLoaderColStrings(loader, u8(name), encoded);
  }

  @Override
  public void loaderColumnTemporal(long loader, String name, int kind, LongBuffer values) {
    nLoaderColTemporal(loader, u8(name), kind, pass(values), values.remaining());
  }

  @Override
  public void loaderFinish(long loader) {
    nLoaderFinish(loader);
  }

  @Override
  public void loaderFree(long loader) {
    nLoaderFree(loader);
  }

  @Override
  public long appenderOpen(long conn, String table) {
    return nAppenderOpen(conn, u8(table));
  }

  @Override
  public void appendBoolean(long appender, boolean value) {
    nAppendBoolean(appender, value);
  }

  @Override
  public void appendLong(long appender, long value) {
    nAppendLong(appender, value);
  }

  @Override
  public void appendDouble(long appender, double value) {
    nAppendDouble(appender, value);
  }

  @Override
  public void appendString(long appender, String value) {
    nAppendString(appender, u8(value));
  }

  @Override
  public void appendBytes(long appender, ByteBuffer value) {
    nAppendBytes(appender, pass(value), value.remaining());
  }

  @Override
  public void appendTemporal(long appender, int kind, long count) {
    nAppendTemporal(appender, kind, count);
  }

  @Override
  public void appendEndRow(long appender) {
    nAppendEndRow(appender);
  }

  @Override
  public void appenderFlush(long appender) {
    nAppenderFlush(appender);
  }

  @Override
  public long appenderBuffered(long appender) {
    return nAppenderBuffered(appender);
  }

  @Override
  public long appenderCommitted(long appender) {
    return nAppenderCommitted(appender);
  }

  @Override
  public int appenderColumns(long appender) {
    return nAppenderColumns(appender);
  }

  @Override
  public String appenderColumnName(long appender, int col) {
    return str(nAppenderColumnName(appender, col));
  }

  @Override
  public long appenderDiscard(long appender) {
    return nAppenderDiscard(appender);
  }

  @Override
  public long appenderClose(long appender) {
    return nAppenderClose(appender);
  }

  @Override
  public void appenderFree(long appender) {
    nAppenderFree(appender);
  }

  @Override
  public long frameNew(String name, long rows, Runnable release) {
    return nFrameNew(u8(name), rows, release);
  }

  @Override
  public void frameColumnInts(
      long frame,
      String name,
      Buffer values,
      long count,
      int bits,
      boolean signed,
      long scale,
      int temporal) {
    nFrameColInt(frame, u8(name), lent(values, name), count, bits, signed, scale, temporal);
  }

  @Override
  public void frameColumnFloats(long frame, String name, Buffer values, long count, int bits) {
    nFrameColFloat(frame, u8(name), lent(values, name), count, bits);
  }

  @Override
  public void frameColumnBooleans(long frame, String name, Buffer bitmap, long count) {
    nFrameColBool(frame, u8(name), lent(bitmap, name), count);
  }

  @Override
  public void frameColumnStrings(
      long frame, String name, Buffer offsets, boolean wide, Buffer data, long count) {
    Buffer characters = lent(data, name);
    nFrameColStr(
        frame, u8(name), lent(offsets, name), wide, characters, byteSize(characters), count);
  }

  @Override
  public void frameColumnViews(
      long frame, String name, Buffer views, List<Buffer> data, long count) {
    Buffer[] buffers = new Buffer[data.size()];
    long[] lengths = new long[buffers.length];
    for (int i = 0; i < buffers.length; i++) {
      buffers[i] = lent(data.get(i), name);
      lengths[i] = byteSize(buffers[i]);
    }
    nFrameColView(frame, u8(name), lent(views, name), buffers, lengths, count);
  }

  @Override
  public void frameFree(long frame) {
    nFrameFree(frame);
  }

  @Override
  public void connRegister(long conn, long frame) {
    nConnRegister(conn, frame);
  }

  @Override
  public boolean connUnregister(long conn, String name) {
    return nConnUnregister(conn, u8(name));
  }

  @Override
  public long connRegisteredCount(long conn) {
    return nConnRegisteredCount(conn);
  }

  @Override
  public String connRegisteredName(long conn, long index) {
    return str(nConnRegisteredName(conn, index));
  }

  // ---- the shim ----

  /**
   * Binds every native below to the shim, and the shim to this class.
   *
   * <p>One method with a name JNI derives, which registers the rest and looks
   * up what it has to call back into. It is done from here rather than from
   * the shim's own load hook because the hook cannot find a class a module
   * path or an application server's loader holds, and the class handed to a
   * static native is the right one by construction.
   *
   * @return whether it bound
   */
  static native boolean nRegister();

  /**
   * Opens libzu and resolves every call this client makes.
   *
   * @param path the library, as UTF-8 bytes
   * @return null if it opened and had everything, and otherwise why not
   */
  static native byte[] nLoad(byte[] path);

  private static native byte[] nVersion();

  private static native long[] nConfigSet(
      long memoryLimit, long threads, boolean readOnly, byte[] key, byte[] value);

  private static native long nDatabaseOpen(
      byte[] path, long memoryLimit, long threads, boolean readOnly);

  private static native long nDatabaseCreate(
      byte[] path, long memoryLimit, long threads, boolean readOnly);

  private static native long nDatabaseMemory(long memoryLimit, long threads, boolean readOnly);

  private static native boolean nDatabaseIsMemory(long db);

  private static native byte[] nDatabasePath(long db);

  private static native void nDatabaseClose(long db);

  private static native long nConnect(long db);

  private static native long nOpen(byte[] path);

  private static native long nCreate(byte[] path);

  private static native long nMemory();

  private static native long nConnDuplicate(long conn);

  private static native void nConnClose(long conn);

  private static native void nConnInterrupt(long conn);

  private static native long nConnRowsRead(long conn);

  private static native byte[] nConnTableName(long conn, int table);

  private static native long nConnSetProgress(long conn, Progress watcher, long intervalMillis);

  private static native void nWatchFree(long cookie);

  private static native boolean nConnInTransaction(long conn);

  private static native void nBegin(long conn, boolean readOnly);

  private static native void nCommit(long conn);

  private static native void nRollback(long conn);

  private static native long nQuery(long conn, byte[] statement);

  private static native long nPrepare(long conn, byte[] statement);

  private static native void nBindLong(long stmt, byte[] name, long value);

  private static native void nBindDouble(long stmt, byte[] name, double value);

  private static native void nBindBoolean(long stmt, byte[] name, boolean value);

  private static native void nBindString(long stmt, byte[] name, byte[] value);

  private static native void nBindTemporal(
      long stmt, byte[] name, int kind, long count, int offsetMinutes);

  private static native void nBindNull(long stmt, byte[] name);

  private static native long nExecute(long stmt);

  private static native void nStmtClose(long stmt);

  private static native long nResultRows(long result);

  private static native int nResultCols(long result);

  private static native byte[] nResultColName(long result, int col);

  private static native int nResultCellType(long result, long row, int col);

  private static native byte[] nResultCellString(long result, long row, int col);

  private static native byte[] nResultGqlstatus(long result);

  private static native int nResultNotices(long result);

  private static native Diagnostic nResultNotice(long result, int index);

  private static native void nResultFree(long result);

  private static native ByteBuffer nColLongs(long result, int col, long rows);

  private static native ByteBuffer nColDoubles(long result, int col, long rows);

  private static native ByteBuffer nColNodeOffsets(long result, int col, long rows);

  private static native ByteBuffer nColValid(long result, int col, long rows);

  private static native long nChunkCount(long result);

  private static native long[] nChunk(long result, long chunk);

  private static native ByteBuffer nChunkLongs(long result, long chunk, int col, long rows);

  private static native ByteBuffer nChunkDoubles(long result, long chunk, int col, long rows);

  private static native ByteBuffer nChunkNodeOffsets(long result, long chunk, int col, long rows);

  private static native ByteBuffer nChunkValid(long result, long chunk, int col, long rows);

  private static native void nResultArrow(long conn, long result, long rowsPerBatch, long stream);

  private static native long nResultCell(long result, long row, int col);

  private static native int nValueType(long value);

  private static native boolean nValueBoolean(long value);

  private static native long nValueLong(long value);

  private static native double nValueDouble(long value);

  private static native byte[] nValueString(long value);

  private static native byte[] nValueBytes(long value);

  private static native long[] nValueTemporal(long value);

  private static native long[] nValueNode(long value);

  private static native long[] nValueRel(long value);

  private static native long nValueLength(long value);

  private static native long nValueAt(long value, long index);

  private static native byte[] nValueField(long value, long index);

  private static native long nLoaderCreate(byte[] path);

  private static native void nLoaderTable(long loader, byte[] nodes, byte[] edges, long rows);

  private static native void nLoaderEdges(long loader, Buffer from, Buffer to, long count);

  private static native void nLoaderColLongs(long loader, byte[] name, Buffer values, long count);

  private static native void nLoaderColDoubles(long loader, byte[] name, Buffer values, long count);

  private static native void nLoaderColBooleans(
      long loader, byte[] name, Buffer values, long count);

  private static native void nLoaderColStrings(long loader, byte[] name, byte[][] values);

  private static native void nLoaderColTemporal(
      long loader, byte[] name, int kind, Buffer values, long count);

  private static native void nLoaderFinish(long loader);

  private static native void nLoaderFree(long loader);

  private static native long nAppenderOpen(long conn, byte[] table);

  private static native void nAppendBoolean(long appender, boolean value);

  private static native void nAppendLong(long appender, long value);

  private static native void nAppendDouble(long appender, double value);

  private static native void nAppendString(long appender, byte[] value);

  private static native void nAppendBytes(long appender, Buffer value, long length);

  private static native void nAppendTemporal(long appender, int kind, long count);

  private static native void nAppendEndRow(long appender);

  private static native void nAppenderFlush(long appender);

  private static native long nAppenderBuffered(long appender);

  private static native long nAppenderCommitted(long appender);

  private static native int nAppenderColumns(long appender);

  private static native byte[] nAppenderColumnName(long appender, int col);

  private static native long nAppenderDiscard(long appender);

  private static native long nAppenderClose(long appender);

  private static native void nAppenderFree(long appender);

  private static native long nFrameNew(byte[] name, long rows, Runnable release);

  private static native void nFrameColInt(
      long frame,
      byte[] name,
      Buffer values,
      long count,
      int bits,
      boolean signed,
      long scale,
      int temporal);

  private static native void nFrameColFloat(
      long frame, byte[] name, Buffer values, long count, int bits);

  private static native void nFrameColBool(long frame, byte[] name, Buffer bitmap, long count);

  private static native void nFrameColStr(
      long frame, byte[] name, Buffer offsets, boolean wide, Buffer data, long dataLength,
      long count);

  private static native void nFrameColView(
      long frame, byte[] name, Buffer views, Buffer[] data, long[] lengths, long count);

  private static native void nFrameFree(long frame);

  private static native void nConnRegister(long conn, long frame);

  private static native boolean nConnUnregister(long conn, byte[] name);

  private static native long nConnRegisteredCount(long conn);

  private static native byte[] nConnRegisteredName(long conn, long index);
}
