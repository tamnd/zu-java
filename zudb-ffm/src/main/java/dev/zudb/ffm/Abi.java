package dev.zudb.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.zudb.spi.ProviderUnavailableException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Every function of the C ABI, looked up once and bound to a method handle.
 *
 * <p>The lookup is where a library that is not the one this client was written
 * against is caught. {@code ZU_ABI_VERSION} is a macro in {@code zu.h} rather
 * than a symbol in the library, so there is nothing to ask at run time and no
 * point pretending otherwise. What there is instead is this: every symbol the
 * client will ever call is resolved here, at load, and a missing one is named
 * in the failure. A library too old to have {@code zu_result_chunk_col_i64}
 * says so on the first line of the stack trace rather than on the call that
 * needed it, three hours into a load.
 *
 * <p>The tiny accessors that only read a field of a struct the engine already
 * has in hand are bound {@linkplain Linker.Option#critical critical}, which
 * skips the thread state transition a downcall usually pays for. They qualify
 * because they are bounded, they do not block and they never call back into
 * Java. {@code zu_query} is none of those things and is bound normally.
 */
final class Abi {

  /** {@code size_t}, which is what the linker says it is on this platform. */
  static final MemoryLayout SIZE_T = Linker.nativeLinker().canonicalLayouts().get("size_t");

  private final SymbolLookup lookup;
  private final Linker linker = Linker.nativeLinker();
  private final Path library;

  final MethodHandle version;

  final MethodHandle errorStatus;
  final MethodHandle errorMessage;
  final MethodHandle errorCode;
  final MethodHandle errorStandardText;
  final MethodHandle errorDocUrl;
  final MethodHandle errorSeverity;
  final MethodHandle errorRetryable;
  final MethodHandle errorPosition;
  final MethodHandle errorOffset;
  final MethodHandle errorExcerpt;
  final MethodHandle errorFree;

  final MethodHandle configSet;

  final MethodHandle databaseOpen;
  final MethodHandle databaseCreate;
  final MethodHandle databaseMemory;
  final MethodHandle databaseIsMemory;
  final MethodHandle databasePath;
  final MethodHandle databaseClose;

  final MethodHandle connect;
  final MethodHandle openOne;
  final MethodHandle createOne;
  final MethodHandle memoryOne;
  final MethodHandle connDuplicate;
  final MethodHandle connClose;
  final MethodHandle connInterrupt;
  final MethodHandle connRowsRead;
  final MethodHandle connSetProgress;
  final MethodHandle connInTransaction;
  final MethodHandle begin;
  final MethodHandle commit;
  final MethodHandle rollback;

  final MethodHandle query;
  final MethodHandle prepare;
  final MethodHandle bindI64;
  final MethodHandle bindF64;
  final MethodHandle bindBool;
  final MethodHandle bindStr;
  final MethodHandle bindTemporal;
  final MethodHandle bindNull;
  final MethodHandle execute;
  final MethodHandle stmtClose;

  final MethodHandle resultRows;
  final MethodHandle resultCols;
  final MethodHandle resultColName;
  final MethodHandle resultCellType;
  final MethodHandle resultCellStr;
  final MethodHandle resultCell;
  final MethodHandle resultFree;
  final MethodHandle resultGqlstatus;
  final MethodHandle resultNotices;
  final MethodHandle resultNotice;

  final MethodHandle colI64;
  final MethodHandle colF64;
  final MethodHandle colNodeOffset;
  final MethodHandle colValid;

  final MethodHandle chunkCount;
  final MethodHandle chunk;
  final MethodHandle chunkColI64;
  final MethodHandle chunkColF64;
  final MethodHandle chunkColNodeOffset;
  final MethodHandle chunkColValid;

  final MethodHandle resultArrow;

  final MethodHandle loaderCreate;
  final MethodHandle loaderTable;
  final MethodHandle loaderEdges;
  final MethodHandle loaderColI64;
  final MethodHandle loaderColF64;
  final MethodHandle loaderColBool;
  final MethodHandle loaderColStr;
  final MethodHandle loaderColTemporal;
  final MethodHandle loaderFinish;
  final MethodHandle loaderFree;

  final MethodHandle appenderOpen;
  final MethodHandle appendBool;
  final MethodHandle appendI64;
  final MethodHandle appendF64;
  final MethodHandle appendStr;
  final MethodHandle appendBytes;
  final MethodHandle appendTemporal;
  final MethodHandle appendEndRow;
  final MethodHandle appenderFlush;
  final MethodHandle appenderBuffered;
  final MethodHandle appenderCommitted;
  final MethodHandle appenderCols;
  final MethodHandle appenderColName;
  final MethodHandle appenderDiscard;
  final MethodHandle appenderClose;
  final MethodHandle appenderFree;

  final MethodHandle frameNew;
  final MethodHandle frameColInt;
  final MethodHandle frameColFloat;
  final MethodHandle frameColBool;
  final MethodHandle frameColStr;
  final MethodHandle frameColView;
  final MethodHandle frameFree;
  final MethodHandle connRegister;
  final MethodHandle connUnregister;
  final MethodHandle connRegisteredCount;
  final MethodHandle connRegisteredName;

  final MethodHandle valueType;
  final MethodHandle valueBool;
  final MethodHandle valueI64;
  final MethodHandle valueF64;
  final MethodHandle valueStr;
  final MethodHandle valueTemporal;
  final MethodHandle valueNode;
  final MethodHandle valueRel;
  final MethodHandle valueLen;
  final MethodHandle valueAt;
  final MethodHandle valueField;

  @SuppressWarnings("restricted")
  Abi(Path library, Arena arena) {
    this.library = library;
    try {
      this.lookup =
          library == null
              ? SymbolLookup.libraryLookup(System.mapLibraryName("zu"), arena)
              : SymbolLookup.libraryLookup(library, arena);
    } catch (IllegalArgumentException e) {
      throw new ProviderUnavailableException(
          "cannot load " + (library == null ? System.mapLibraryName("zu") : library) + ": " + e.getMessage(),
          e);
    }

    version = h("zu_version", FunctionDescriptor.of(ADDRESS));

    errorStatus = critical("zu_error_status", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    errorMessage = h("zu_error_message", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    errorCode = h("zu_error_code", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    errorStandardText = h("zu_error_standard_text", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    errorDocUrl = h("zu_error_doc_url", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    errorSeverity = critical("zu_error_severity", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    errorRetryable = critical("zu_error_retryable", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    errorPosition = h("zu_error_position", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    errorOffset = h("zu_error_offset", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    errorExcerpt = h("zu_error_excerpt", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    errorFree = h("zu_error_free", FunctionDescriptor.ofVoid(ADDRESS));

    configSet =
        h("zu_config_set", FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, SIZE_T, ADDRESS));

    databaseOpen =
        h("zu_database_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, ADDRESS, ADDRESS, ADDRESS));
    databaseCreate =
        h("zu_database_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, ADDRESS, ADDRESS, ADDRESS));
    databaseMemory = h("zu_database_memory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    databaseIsMemory = critical("zu_database_is_memory", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    databasePath = h("zu_database_path", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    databaseClose = h("zu_database_close", FunctionDescriptor.ofVoid(ADDRESS));

    connect = h("zu_connect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    openOne = h("zu_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    createOne = h("zu_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    memoryOne = h("zu_memory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    connDuplicate = h("zu_conn_duplicate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    connClose = h("zu_conn_close", FunctionDescriptor.ofVoid(ADDRESS));
    connInterrupt = h("zu_conn_interrupt", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    connRowsRead = h("zu_conn_rows_read", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    connSetProgress = h("zu_conn_set_progress", FunctionDescriptor.of(
        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    connInTransaction = h("zu_conn_in_transaction", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    begin = h("zu_begin", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    commit = h("zu_commit", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    rollback = h("zu_rollback", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    query = h("zu_query", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    prepare = h("zu_prepare", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    bindI64 = h("zu_bind_i64", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, JAVA_LONG));
    bindF64 = h("zu_bind_f64", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, JAVA_DOUBLE));
    bindBool = h("zu_bind_bool", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, JAVA_INT));
    bindStr =
        h("zu_bind_str", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, SIZE_T));
    bindTemporal =
        h(
            "zu_bind_temporal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, JAVA_INT, JAVA_LONG, JAVA_INT));
    bindNull = h("zu_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T));
    execute = h("zu_execute", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    stmtClose = h("zu_stmt_close", FunctionDescriptor.ofVoid(ADDRESS));

    resultRows = critical("zu_result_rows", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    resultCols = critical("zu_result_cols", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    resultColName =
        h("zu_result_col_name", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    resultCellType =
        h("zu_result_cell_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    resultCellStr =
        h(
            "zu_result_cell_str",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
    resultCell =
        h("zu_result_cell", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    resultFree = h("zu_result_free", FunctionDescriptor.ofVoid(ADDRESS));
    resultGqlstatus = h("zu_result_gqlstatus", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    resultNotices = h("zu_result_notices", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    resultNotice = h("zu_result_notice", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    colI64 = h("zu_result_col_i64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    colF64 = h("zu_result_col_f64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    colNodeOffset =
        h("zu_result_col_node_offset", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    colValid = h("zu_result_col_valid", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    chunkCount = critical("zu_result_chunk_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    chunk =
        h("zu_result_chunk", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    chunkColI64 =
        h(
            "zu_result_chunk_col_i64",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    chunkColF64 =
        h(
            "zu_result_chunk_col_f64",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    chunkColNodeOffset =
        h(
            "zu_result_chunk_col_node_offset",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    chunkColValid =
        h(
            "zu_result_chunk_col_valid",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));

    resultArrow =
        h(
            "zu_result_arrow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    loaderCreate =
        h("zu_loader_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    loaderTable =
        h(
            "zu_loader_table",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, SIZE_T, JAVA_LONG, ADDRESS));
    loaderEdges =
        h(
            "zu_loader_edges",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    loaderColI64 =
        h(
            "zu_loader_col_i64",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, JAVA_LONG, ADDRESS));
    loaderColF64 =
        h(
            "zu_loader_col_f64",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, JAVA_LONG, ADDRESS));
    loaderColBool =
        h(
            "zu_loader_col_bool",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, JAVA_LONG, ADDRESS));
    loaderColStr =
        h(
            "zu_loader_col_str",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    loaderColTemporal =
        h(
            "zu_loader_col_temporal",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, ADDRESS, SIZE_T, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    loaderFinish = h("zu_loader_finish", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    loaderFree = h("zu_loader_free", FunctionDescriptor.ofVoid(ADDRESS));

    appenderOpen =
        h(
            "zu_appender_open",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    appendBool = h("zu_append_bool", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    appendI64 = h("zu_append_i64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    appendF64 = h("zu_append_f64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, ADDRESS));
    appendStr =
        h("zu_append_str", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS));
    appendBytes =
        h("zu_append_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS));
    appendTemporal =
        h(
            "zu_append_temporal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    appendEndRow = h("zu_append_end_row", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderFlush = h("zu_appender_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderBuffered = h("zu_appender_buffered", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderCommitted =
        h("zu_appender_committed", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderCols = h("zu_appender_cols", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderColName =
        h("zu_appender_col_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    appenderDiscard = h("zu_appender_discard", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    appenderClose =
        h("zu_appender_close", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    appenderFree = h("zu_appender_free", FunctionDescriptor.ofVoid(ADDRESS));

    frameNew =
        h(
            "zu_frame_new",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, SIZE_T, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    frameColInt =
        h(
            "zu_frame_col_int",
            FunctionDescriptor.of(
                JAVA_INT,
                ADDRESS,
                ADDRESS,
                SIZE_T,
                ADDRESS,
                JAVA_LONG,
                JAVA_INT,
                JAVA_INT,
                JAVA_LONG,
                JAVA_INT,
                ADDRESS));
    frameColFloat =
        h(
            "zu_frame_col_float",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    frameColBool =
        h(
            "zu_frame_col_bool",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, JAVA_LONG, ADDRESS));
    frameColStr =
        h(
            "zu_frame_col_str",
            FunctionDescriptor.of(
                JAVA_INT,
                ADDRESS,
                ADDRESS,
                SIZE_T,
                ADDRESS,
                JAVA_INT,
                ADDRESS,
                SIZE_T,
                JAVA_LONG,
                ADDRESS));
    frameColView =
        h(
            "zu_frame_col_view",
            FunctionDescriptor.of(
                JAVA_INT,
                ADDRESS,
                ADDRESS,
                SIZE_T,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                SIZE_T,
                JAVA_LONG,
                ADDRESS));
    frameFree = h("zu_frame_free", FunctionDescriptor.ofVoid(ADDRESS));
    connRegister = h("zu_conn_register", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    connUnregister =
        h(
            "zu_conn_unregister",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS));
    connRegisteredCount =
        h("zu_conn_registered_count", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    connRegisteredName =
        h("zu_conn_registered_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

    valueType = critical("zu_value_type", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    valueBool = h("zu_value_bool", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    valueI64 = h("zu_value_i64", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    valueF64 = h("zu_value_f64", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    valueStr = h("zu_value_str", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    valueTemporal =
        h("zu_value_temporal", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    valueNode = h("zu_value_node", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    valueRel =
        h("zu_value_rel", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    valueLen = critical("zu_value_len", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    valueAt = h("zu_value_at", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    valueField =
        h("zu_value_field", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
  }

  /** Where this came from, for the message the loader prints. */
  Path library() {
    return library;
  }

  @SuppressWarnings("restricted")
  private MethodHandle h(String name, FunctionDescriptor descriptor) {
    Shapes.down(descriptor, false);
    return linker.downcallHandle(find(name), descriptor);
  }

  @SuppressWarnings("restricted")
  private MethodHandle critical(String name, FunctionDescriptor descriptor) {
    Shapes.down(descriptor, true);
    return linker.downcallHandle(find(name), descriptor, Linker.Option.critical(false));
  }

  private MemorySegment find(String name) {
    return lookup
        .find(name)
        .orElseThrow(
            () ->
                new ProviderUnavailableException(
                    (library == null ? "the libzu on the library path" : library.toString())
                        + " has no "
                        + name
                        + ", so it is not a libzu this client can drive: this client speaks ABI "
                        + dev.zudb.Zu.ABI_VERSION
                        + " and the library is older"));
  }
}
