/*
 * The zu C ABI, as JNI, for the JDKs that have no Panama.
 *
 * This file is the whole of the native code in this repository. It is
 * about a hundred one-line functions over zu.h and four things worth
 * reading first.
 *
 * It does not link against libzu. The API module decides which library
 * to load, from a property, an environment variable, an artifact on the
 * classpath or the platform's own search, and hands one path to
 * whichever provider it tries. So this shim is built once per platform
 * against nothing but the JDK and the C library, and opens libzu itself
 * in n_load. That is also what makes the seven builds cheap: none of
 * them needs a Rust toolchain, and a shim built in 2026 opens a libzu
 * built tomorrow as long as the ABI revision still matches.
 *
 * Strings cross as byte arrays and never as jstring. JNI's
 * GetStringUTFChars answers modified UTF-8, which spells a character
 * outside the basic plane as a surrogate pair in six bytes and a NUL as
 * two. The engine validates real UTF-8 and would refuse the first
 * emoji anybody stored. NewStringUTF has the same fault in the other
 * direction. So the Java side encodes and decodes, and this side sees
 * bytes and a length, which is what the ABI wants anyway.
 *
 * There is one exported symbol with a mangled name, n_register, and
 * everything else is bound by RegisterNatives from the table at the
 * bottom. That is not tidiness: JNI_OnLoad cannot FindClass anything a
 * custom loader or the module path holds, and the class handed to a
 * static native is exactly the right one to register against.
 *
 * A failure is thrown from here, built by the Java helpers, so that the
 * mapping from a status and a GQLSTATUS code to an exception class
 * stays in the one place both providers share.
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "zu.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

/* ------------------------------------------------------------------ */
/* the library                                                         */
/* ------------------------------------------------------------------ */

/*
 * Every call this client makes, as a pointer resolved once. The list is
 * the whole of what the shim depends on, so a library missing any of it
 * is refused by name at load rather than found by a segfault later.
 */
#define ZU_SYMBOLS(X)                                                                     \
  X(version, const char *, (void))                                                        \
  X(error_status, zu_status, (const zu_error *))                                          \
  X(error_message, const char *, (const zu_error *, size_t *))                            \
  X(error_code, const char *, (const zu_error *, size_t *))                               \
  X(error_standard_text, const char *, (const zu_error *, size_t *))                      \
  X(error_doc_url, const char *, (const zu_error *, size_t *))                            \
  X(error_severity, int32_t, (const zu_error *))                                          \
  X(error_retryable, int32_t, (const zu_error *))                                         \
  X(error_position, zu_status, (const zu_error *, uint32_t *, uint32_t *))                \
  X(error_offset, zu_status, (const zu_error *, uint32_t *))                              \
  X(error_excerpt, const char *, (const zu_error *, size_t *))                            \
  X(error_free, void, (zu_error *))                                                       \
  X(config_set, zu_status,                                                                \
    (zu_config *, const char *, size_t, const char *, size_t, zu_error **))               \
  X(database_open, zu_status,                                                             \
    (const char *, size_t, const zu_config *, zu_database **, zu_error **))               \
  X(database_create, zu_status,                                                           \
    (const char *, size_t, const zu_config *, zu_database **, zu_error **))               \
  X(database_memory, zu_status, (const zu_config *, zu_database **, zu_error **))         \
  X(database_is_memory, zu_status, (const zu_database *))                                 \
  X(database_path, zu_status, (const zu_database *, const char **, size_t *))             \
  X(database_close, void, (zu_database *))                                                \
  X(connect, zu_status, (zu_database *, zu_conn **, zu_error **))                         \
  X(open, zu_status, (const char *, size_t, zu_conn **, zu_error **))                     \
  X(create, zu_status, (const char *, size_t, zu_conn **, zu_error **))                   \
  X(memory, zu_status, (zu_conn **, zu_error **))                                         \
  X(conn_duplicate, zu_status, (zu_conn *, zu_conn **, zu_error **))                      \
  X(conn_close, void, (zu_conn *))                                                        \
  X(conn_interrupt, zu_status, (zu_conn *))                                               \
  X(conn_rows_read, zu_status, (zu_conn *, uint64_t *))                                   \
  X(conn_set_progress, zu_status, (zu_conn *, zu_progress_fn, void *, uint64_t))          \
  X(query, zu_status, (zu_conn *, const char *, size_t, zu_result **, zu_error **))       \
  X(prepare, zu_status, (zu_conn *, const char *, size_t, zu_stmt **, zu_error **))       \
  X(bind_i64, zu_status, (zu_stmt *, const char *, size_t, int64_t))                      \
  X(bind_f64, zu_status, (zu_stmt *, const char *, size_t, double))                       \
  X(bind_bool, zu_status, (zu_stmt *, const char *, size_t, int))                         \
  X(bind_str, zu_status, (zu_stmt *, const char *, size_t, const char *, size_t))         \
  X(bind_temporal, zu_status, (zu_stmt *, const char *, size_t, int32_t, int64_t, int32_t)) \
  X(bind_null, zu_status, (zu_stmt *, const char *, size_t))                              \
  X(execute, zu_status, (zu_stmt *, zu_result **, zu_error **))                           \
  X(stmt_close, void, (zu_stmt *))                                                        \
  X(begin, zu_status, (zu_conn *, int32_t, zu_error **))                                   \
  X(commit, zu_status, (zu_conn *, zu_error **))                                          \
  X(rollback, zu_status, (zu_conn *, zu_error **))                                        \
  X(conn_in_transaction, zu_status, (zu_conn *, int32_t *))                               \
  X(result_rows, uint64_t, (const zu_result *))                                           \
  X(result_cols, uint32_t, (const zu_result *))                                           \
  X(result_col_name, zu_status, (const zu_result *, uint32_t, const char **, size_t *))   \
  X(result_cell_type, zu_status, (const zu_result *, uint64_t, uint32_t, int32_t *))      \
  X(result_col_i64, zu_status, (zu_result *, uint32_t, const int64_t **))                 \
  X(result_col_f64, zu_status, (zu_result *, uint32_t, const double **))                  \
  X(result_col_node_offset, zu_status, (zu_result *, uint32_t, const uint64_t **))        \
  X(result_col_valid, zu_status, (zu_result *, uint32_t, const uint8_t **))               \
  X(result_chunk_count, uint64_t, (const zu_result *))                                    \
  X(result_chunk, zu_status, (const zu_result *, uint64_t, uint64_t *, uint64_t *))       \
  X(result_chunk_col_i64, zu_status, (zu_result *, uint64_t, uint32_t, const int64_t **)) \
  X(result_chunk_col_f64, zu_status, (zu_result *, uint64_t, uint32_t, const double **))  \
  X(result_chunk_col_node_offset, zu_status,                                              \
    (zu_result *, uint64_t, uint32_t, const uint64_t **))                                 \
  X(result_chunk_col_valid, zu_status, (zu_result *, uint64_t, uint32_t, const uint8_t **)) \
  X(result_cell_str, zu_status, (zu_result *, uint64_t, uint32_t, const char **, size_t *)) \
  X(result_cell, zu_status, (const zu_result *, uint64_t, uint32_t, const zu_value **))   \
  X(result_free, void, (zu_result *))                                                     \
  X(result_arrow, zu_status,                                                              \
    (zu_conn *, zu_result **, uint64_t, struct ArrowArrayStream *, zu_error **))          \
  X(result_gqlstatus, const char *, (zu_result *, size_t *))                              \
  X(result_notices, uint32_t, (zu_result *))                                              \
  X(result_notice, zu_status, (zu_result *, uint32_t, zu_error **))                       \
  X(value_type, int32_t, (const zu_value *))                                              \
  X(value_bool, zu_status, (const zu_value *, int32_t *))                                 \
  X(value_i64, zu_status, (const zu_value *, int64_t *))                                  \
  X(value_f64, zu_status, (const zu_value *, double *))                                   \
  X(value_str, zu_status, (const zu_value *, const char **, size_t *))                    \
  X(value_temporal, zu_status, (const zu_value *, int32_t *, int64_t *, int32_t *))       \
  X(value_node, zu_status, (const zu_value *, uint32_t *, uint64_t *))                    \
  X(value_rel, zu_status, (const zu_value *, uint32_t *, uint64_t *, uint64_t *))         \
  X(value_len, uint64_t, (const zu_value *))                                              \
  X(value_at, zu_status, (const zu_value *, uint64_t, const zu_value **))                 \
  X(value_field, zu_status, (const zu_value *, uint64_t, const char **, size_t *))        \
  X(loader_create, zu_status, (const char *, size_t, zu_loader **, zu_error **))          \
  X(loader_table, zu_status,                                                              \
    (zu_loader *, const char *, size_t, const char *, size_t, uint64_t, zu_error **))     \
  X(loader_edges, zu_status,                                                              \
    (zu_loader *, const uint32_t *, const uint32_t *, uint64_t, zu_error **))             \
  X(loader_col_i64, zu_status,                                                            \
    (zu_loader *, const char *, size_t, const int64_t *, uint64_t, zu_error **))          \
  X(loader_col_f64, zu_status,                                                            \
    (zu_loader *, const char *, size_t, const double *, uint64_t, zu_error **))           \
  X(loader_col_bool, zu_status,                                                           \
    (zu_loader *, const char *, size_t, const int32_t *, uint64_t, zu_error **))          \
  X(loader_col_str, zu_status,                                                            \
    (zu_loader *, const char *, size_t, const char *const *, const size_t *, uint64_t,    \
     zu_error **))                                                                        \
  X(loader_col_temporal, zu_status,                                                       \
    (zu_loader *, const char *, size_t, int32_t, const int64_t *, uint64_t, zu_error **)) \
  X(loader_finish, zu_status, (zu_loader *, zu_error **))                                 \
  X(loader_free, void, (zu_loader *))                                                     \
  X(appender_open, zu_status,                                                             \
    (zu_conn *, const char *, size_t, zu_appender **, zu_error **))                       \
  X(append_bool, zu_status, (zu_appender *, int32_t, zu_error **))                        \
  X(append_i64, zu_status, (zu_appender *, int64_t, zu_error **))                         \
  X(append_f64, zu_status, (zu_appender *, double, zu_error **))                          \
  X(append_str, zu_status, (zu_appender *, const char *, size_t, zu_error **))            \
  X(append_bytes, zu_status, (zu_appender *, const uint8_t *, size_t, zu_error **))       \
  X(append_temporal, zu_status, (zu_appender *, int32_t, int64_t, zu_error **))           \
  X(append_end_row, zu_status, (zu_appender *, zu_error **))                              \
  X(appender_flush, zu_status, (zu_appender *, zu_error **))                              \
  X(appender_buffered, zu_status, (zu_appender *, uint64_t *))                            \
  X(appender_committed, zu_status, (zu_appender *, uint64_t *))                           \
  X(appender_cols, zu_status, (zu_appender *, uint32_t *))                                \
  X(appender_col_name, const char *, (zu_appender *, uint32_t, size_t *))                 \
  X(appender_discard, zu_status, (zu_appender *, uint64_t *))                             \
  X(appender_close, zu_status, (zu_appender *, uint64_t *, zu_error **))                  \
  X(appender_free, void, (zu_appender *))                                                 \
  X(frame_new, zu_status,                                                                 \
    (const char *, size_t, uint64_t, void *, void (*)(void *), zu_frame **, zu_error **)) \
  X(frame_col_int, zu_status,                                                             \
    (zu_frame *, const char *, size_t, const void *, uint64_t, int32_t, int32_t, int64_t, \
     int32_t, zu_error **))                                                               \
  X(frame_col_float, zu_status,                                                           \
    (zu_frame *, const char *, size_t, const void *, uint64_t, int32_t, zu_error **))     \
  X(frame_col_bool, zu_status,                                                            \
    (zu_frame *, const char *, size_t, const void *, uint64_t, zu_error **))              \
  X(frame_col_str, zu_status,                                                             \
    (zu_frame *, const char *, size_t, const void *, int32_t, const void *, size_t,       \
     uint64_t, zu_error **))                                                              \
  X(frame_col_view, zu_status,                                                            \
    (zu_frame *, const char *, size_t, const void *, const void *const *, const size_t *, \
     size_t, uint64_t, zu_error **))                                                      \
  X(frame_free, void, (zu_frame *))                                                       \
  X(conn_register, zu_status, (zu_conn *, zu_frame *, zu_error **))                       \
  X(conn_unregister, zu_status, (zu_conn *, const char *, size_t, int32_t *, zu_error **)) \
  X(conn_registered_count, zu_status, (zu_conn *, uint64_t *))                            \
  X(conn_registered_name, const char *, (zu_conn *, uint64_t, size_t *))

#define ZU_DECLARE(name, ret, params) static ret (*p_##name) params;
ZU_SYMBOLS(ZU_DECLARE)
#undef ZU_DECLARE

/* ------------------------------------------------------------------ */
/* what this side remembers                                            */
/* ------------------------------------------------------------------ */

static JavaVM *vm;
static jclass c_binding;    /* global ref, for the two static helpers */
static jmethodID m_diagnostic;
static jmethodID m_misuse;
static jmethodID m_to_exception;
static jmethodID m_progress_at;
static jmethodID m_runnable_run;

#define H(type, handle) ((type *)(intptr_t)(handle))
#define A(handle) ((jlong)(intptr_t)(handle))

/* ------------------------------------------------------------------ */
/* threads                                                            */
/* ------------------------------------------------------------------ */

/*
 * A callback arrives on a thread of the engine's, which the JVM has
 * never seen. Attaching for the call and detaching after it is not the
 * cheapest arrangement, but it is the one that is correct when the
 * engine's pool retires a worker: a thread that exits while still
 * attached takes the process with it, and nothing here is told when a
 * worker goes.
 */
static JNIEnv *attach(int *attached) {
  void *env = NULL;
  *attached = 0;
  if ((*vm)->GetEnv(vm, &env, JNI_VERSION_1_8) == JNI_OK) {
    return (JNIEnv *)env;
  }
  if ((*vm)->AttachCurrentThreadAsDaemon(vm, &env, NULL) != JNI_OK) {
    return NULL;
  }
  *attached = 1;
  return (JNIEnv *)env;
}

static void detach(int attached) {
  if (attached) {
    (*vm)->DetachCurrentThread(vm);
  }
}

/* ------------------------------------------------------------------ */
/* strings and arrays                                                 */
/* ------------------------------------------------------------------ */

/* A byte array borrowed for the length of one call. */
typedef struct {
  jbyteArray array;
  jbyte *data;
  jsize length;
} borrowed;

static int borrow(JNIEnv *env, jbyteArray array, borrowed *out) {
  out->array = array;
  out->data = NULL;
  out->length = 0;
  if (array == NULL) {
    return 1;
  }
  out->data = (*env)->GetByteArrayElements(env, array, NULL);
  if (out->data == NULL) {
    return 0; /* the out of memory error is already pending */
  }
  out->length = (*env)->GetArrayLength(env, array);
  return 1;
}

static void giveback(JNIEnv *env, borrowed *b) {
  if (b->data != NULL) {
    (*env)->ReleaseByteArrayElements(env, b->array, b->data, JNI_ABORT);
  }
}

#define S(b) ((const char *)(b).data)
#define L(b) ((size_t)(b).length)

/* A run of bytes as a Java array, or null for a pointer that is null. */
static jbyteArray bytes(JNIEnv *env, const char *p, size_t len) {
  jbyteArray a;
  if (p == NULL) {
    return NULL;
  }
  if (len > (size_t)INT32_MAX) {
    len = (size_t)INT32_MAX;
  }
  a = (*env)->NewByteArray(env, (jsize)len);
  if (a == NULL) {
    return NULL;
  }
  if (len > 0) {
    (*env)->SetByteArrayRegion(env, a, 0, (jsize)len, (const jbyte *)p);
  }
  return a;
}

/* The same for a NUL terminated string, which is what zu_version is. */
static jbyteArray cstring(JNIEnv *env, const char *p) {
  return p == NULL ? NULL : bytes(env, p, strlen(p));
}

static jlongArray longs(JNIEnv *env, const jlong *values, jsize count) {
  jlongArray a = (*env)->NewLongArray(env, count);
  if (a != NULL) {
    (*env)->SetLongArrayRegion(env, a, 0, count, values);
  }
  return a;
}

/*
 * The address of a direct buffer, which is where the Java side has
 * already put whatever the engine is about to read. A buffer that is
 * not direct never reaches here: the Java side either copies it into
 * one, for a call that reads and is done, or refuses it, for a frame
 * that keeps the pointer.
 */
static void *address(JNIEnv *env, jobject buffer) {
  return buffer == NULL ? NULL : (*env)->GetDirectBufferAddress(env, buffer);
}

/* ------------------------------------------------------------------ */
/* failures                                                           */
/* ------------------------------------------------------------------ */

/* One of the const char * accessors on a zu_error, as a Java array. */
static jbyteArray field(JNIEnv *env, const zu_error *e,
                        const char *(*get)(const zu_error *, size_t *)) {
  size_t len = 0;
  const char *p = get(e, &len);
  return bytes(env, p, len);
}

/*
 * Turns what the ABI answered into the exception it names and throws
 * it, then frees the error. The record is built in Java because that is
 * where the mapping from a GQLSTATUS class to an exception class lives,
 * and it lives in one place so that the two providers cannot come to
 * differ about it.
 */
static void raise(JNIEnv *env, zu_status status, zu_error *e, const char *what) {
  jobject record;
  jobject thrown;
  uint32_t line = 0;
  uint32_t column = 0;
  uint32_t offset = 0;
  jint jline = -1;
  jint jcolumn = -1;
  jint joffset = -1;

  if (e == NULL) {
    jstring name = (*env)->NewStringUTF(env, what);
    if (name == NULL) {
      return;
    }
    thrown = (*env)->CallStaticObjectMethod(env, c_binding, m_misuse, (jint)status, name);
    (*env)->DeleteLocalRef(env, name);
    if (thrown != NULL) {
      (*env)->Throw(env, (jthrowable)thrown);
    }
    return;
  }

  if (p_error_position(e, &line, &column) == ZU_OK) {
    jline = (jint)line;
    jcolumn = (jint)column;
  }
  if (p_error_offset(e, &offset) == ZU_OK) {
    joffset = (jint)offset;
  }

  record = (*env)->CallStaticObjectMethod(
      env, c_binding, m_diagnostic, (jint)p_error_status(e), field(env, e, p_error_message),
      field(env, e, p_error_code), field(env, e, p_error_standard_text),
      (jint)p_error_severity(e), jline, jcolumn, joffset, field(env, e, p_error_excerpt),
      field(env, e, p_error_doc_url), p_error_retryable(e) == 1 ? JNI_TRUE : JNI_FALSE);
  p_error_free(e);
  if (record == NULL) {
    return; /* something is already pending, and it is the truer failure */
  }
  thrown = (*env)->CallObjectMethod(env, record, m_to_exception);
  if (thrown != NULL) {
    (*env)->Throw(env, (jthrowable)thrown);
  }
}

/* The record a notice is, which is the same reading without the throw. */
static jobject record(JNIEnv *env, zu_error *e) {
  jobject out;
  uint32_t line = 0;
  uint32_t column = 0;
  uint32_t offset = 0;
  jint jline = -1;
  jint jcolumn = -1;
  jint joffset = -1;

  if (p_error_position(e, &line, &column) == ZU_OK) {
    jline = (jint)line;
    jcolumn = (jint)column;
  }
  if (p_error_offset(e, &offset) == ZU_OK) {
    joffset = (jint)offset;
  }
  out = (*env)->CallStaticObjectMethod(
      env, c_binding, m_diagnostic, (jint)p_error_status(e), field(env, e, p_error_message),
      field(env, e, p_error_code), field(env, e, p_error_standard_text),
      (jint)p_error_severity(e), jline, jcolumn, joffset, field(env, e, p_error_excerpt),
      field(env, e, p_error_doc_url), p_error_retryable(e) == 1 ? JNI_TRUE : JNI_FALSE);
  p_error_free(e);
  return out;
}

/* A status that is not OK and carries no error of its own. */
#define FAIL_IF(env, st, err, what)              \
  do {                                           \
    if ((st) != ZU_OK) {                         \
      raise((env), (st), (err), (what));         \
      return;                                    \
    }                                            \
  } while (0)

#define FAIL_IF_V(env, st, err, what, value)     \
  do {                                           \
    if ((st) != ZU_OK) {                         \
      raise((env), (st), (err), (what));         \
      return (value);                            \
    }                                            \
  } while (0)

/* ------------------------------------------------------------------ */
/* loading                                                            */
/* ------------------------------------------------------------------ */

#ifdef _WIN32
typedef HMODULE handle;
static handle lib_open(const char *path) {
  int wide = MultiByteToWideChar(CP_UTF8, 0, path, -1, NULL, 0);
  wchar_t *w;
  handle h;
  if (wide <= 0) {
    return NULL;
  }
  w = (wchar_t *)calloc((size_t)wide, sizeof(wchar_t));
  if (w == NULL) {
    return NULL;
  }
  MultiByteToWideChar(CP_UTF8, 0, path, -1, w, wide);
  h = LoadLibraryW(w);
  free(w);
  return h;
}
static void *lib_sym(handle h, const char *name) {
  return (void *)(intptr_t)GetProcAddress(h, name);
}
#else
typedef void *handle;
static handle lib_open(const char *path) { return dlopen(path, RTLD_NOW | RTLD_LOCAL); }
static void *lib_sym(handle h, const char *name) { return dlsym(h, name); }
#endif

static handle library;

/*
 * Opens libzu and binds every call in it.
 *
 * Answers null for a library that opened and had everything, and
 * otherwise a sentence saying which of the two went wrong. A provider
 * turns that into ProviderUnavailableException, which is not a failure
 * of the program but a fact about this machine.
 */
static jbyteArray n_load(JNIEnv *env, jclass self, jbyteArray path) {
  borrowed p;
  char *copy;
  handle h;
  (void)self;

  if (library != NULL) {
    return NULL; /* already bound, and a second library would be a second engine */
  }
  if (!borrow(env, path, &p)) {
    return NULL;
  }
  copy = (char *)malloc(L(p) + 1);
  if (copy == NULL) {
    giveback(env, &p);
    return cstring(env, "out of memory");
  }
  memcpy(copy, S(p), L(p));
  copy[L(p)] = '\0';
  giveback(env, &p);

  h = lib_open(copy);
  if (h == NULL) {
    char message[1024];
#ifdef _WIN32
    snprintf(message, sizeof message, "%s did not load (error %lu)", copy,
             (unsigned long)GetLastError());
#else
    const char *why = dlerror();
    snprintf(message, sizeof message, "%s did not load: %s", copy, why == NULL ? "" : why);
#endif
    free(copy);
    return cstring(env, message);
  }
  free(copy);

#define ZU_BIND(name, ret, params)                            \
  p_##name = (ret(*) params)lib_sym(h, "zu_" #name);          \
  if (p_##name == NULL) {                                     \
    return cstring(env, "this library has no zu_" #name       \
                        ", so it is not a zu of this ABI");   \
  }
  ZU_SYMBOLS(ZU_BIND)
#undef ZU_BIND

  library = h;
  return NULL;
}

/* ------------------------------------------------------------------ */
/* the calls                                                          */
/* ------------------------------------------------------------------ */

static jbyteArray n_version(JNIEnv *env, jclass self) {
  (void)self;
  return cstring(env, p_version());
}

static void config(zu_config *cfg, jlong memory, jlong threads, jboolean read_only) {
  memset(cfg, 0, sizeof *cfg);
  cfg->struct_size = sizeof *cfg;
  cfg->memory_limit = (size_t)memory;
  cfg->threads = (size_t)threads;
  cfg->read_only = read_only == JNI_TRUE ? 1 : 0;
}

static jlongArray n_config_set(JNIEnv *env, jclass self, jlong memory, jlong threads,
                               jboolean read_only, jbyteArray key, jbyteArray value) {
  zu_config cfg;
  zu_error *e = NULL;
  zu_status st;
  borrowed k;
  borrowed v;
  jlong out[3];
  (void)self;

  if (!borrow(env, key, &k)) {
    return NULL;
  }
  if (!borrow(env, value, &v)) {
    giveback(env, &k);
    return NULL;
  }
  config(&cfg, memory, threads, read_only);
  st = p_config_set(&cfg, S(k), L(k), S(v), L(v), &e);
  giveback(env, &k);
  giveback(env, &v);
  FAIL_IF_V(env, st, e, "zu_config_set", NULL);
  out[0] = (jlong)cfg.memory_limit;
  out[1] = (jlong)cfg.threads;
  out[2] = (jlong)cfg.read_only;
  return longs(env, out, 3);
}

static jlong open_or_create(JNIEnv *env, jbyteArray path, jlong memory, jlong threads,
                            jboolean read_only, int creating) {
  zu_config cfg;
  zu_database *db = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed p;

  if (!borrow(env, path, &p)) {
    return 0;
  }
  config(&cfg, memory, threads, read_only);
  st = creating ? p_database_create(S(p), L(p), &cfg, &db, &e)
                : p_database_open(S(p), L(p), &cfg, &db, &e);
  giveback(env, &p);
  FAIL_IF_V(env, st, e, creating ? "zu_database_create" : "zu_database_open", 0);
  return A(db);
}

static jlong n_database_open(JNIEnv *env, jclass self, jbyteArray path, jlong memory,
                             jlong threads, jboolean read_only) {
  (void)self;
  return open_or_create(env, path, memory, threads, read_only, 0);
}

static jlong n_database_create(JNIEnv *env, jclass self, jbyteArray path, jlong memory,
                               jlong threads, jboolean read_only) {
  (void)self;
  return open_or_create(env, path, memory, threads, read_only, 1);
}

static jlong n_database_memory(JNIEnv *env, jclass self, jlong memory, jlong threads,
                               jboolean read_only) {
  zu_config cfg;
  zu_database *db = NULL;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  config(&cfg, memory, threads, read_only);
  st = p_database_memory(&cfg, &db, &e);
  FAIL_IF_V(env, st, e, "zu_database_memory", 0);
  return A(db);
}

static jboolean n_database_is_memory(JNIEnv *env, jclass self, jlong db) {
  (void)env;
  (void)self;
  return p_database_is_memory(H(zu_database, db)) == ZU_OK ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray n_database_path(JNIEnv *env, jclass self, jlong db) {
  const char *p = NULL;
  size_t len = 0;
  zu_status st = p_database_path(H(zu_database, db), &p, &len);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_database_path", NULL);
  return bytes(env, p, len);
}

static void n_database_close(JNIEnv *env, jclass self, jlong db) {
  (void)env;
  (void)self;
  p_database_close(H(zu_database, db));
}

static jlong n_connect(JNIEnv *env, jclass self, jlong db) {
  zu_conn *conn = NULL;
  zu_error *e = NULL;
  zu_status st = p_connect(H(zu_database, db), &conn, &e);
  (void)self;
  FAIL_IF_V(env, st, e, "zu_connect", 0);
  return A(conn);
}

static jlong path_conn(JNIEnv *env, jbyteArray path, int creating) {
  zu_conn *conn = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed p;

  if (!borrow(env, path, &p)) {
    return 0;
  }
  st = creating ? p_create(S(p), L(p), &conn, &e) : p_open(S(p), L(p), &conn, &e);
  giveback(env, &p);
  FAIL_IF_V(env, st, e, creating ? "zu_create" : "zu_open", 0);
  return A(conn);
}

static jlong n_open(JNIEnv *env, jclass self, jbyteArray path) {
  (void)self;
  return path_conn(env, path, 0);
}

static jlong n_create(JNIEnv *env, jclass self, jbyteArray path) {
  (void)self;
  return path_conn(env, path, 1);
}

static jlong n_memory(JNIEnv *env, jclass self) {
  zu_conn *conn = NULL;
  zu_error *e = NULL;
  zu_status st = p_memory(&conn, &e);
  (void)self;
  FAIL_IF_V(env, st, e, "zu_memory", 0);
  return A(conn);
}

static jlong n_conn_duplicate(JNIEnv *env, jclass self, jlong conn) {
  zu_conn *out = NULL;
  zu_error *e = NULL;
  zu_status st = p_conn_duplicate(H(zu_conn, conn), &out, &e);
  (void)self;
  FAIL_IF_V(env, st, e, "zu_conn_duplicate", 0);
  return A(out);
}

static void n_conn_close(JNIEnv *env, jclass self, jlong conn) {
  (void)env;
  (void)self;
  p_conn_close(H(zu_conn, conn));
}

static void n_conn_interrupt(JNIEnv *env, jclass self, jlong conn) {
  zu_status st = p_conn_interrupt(H(zu_conn, conn));
  (void)self;
  FAIL_IF(env, st, NULL, "zu_conn_interrupt");
}

static jlong n_conn_rows_read(JNIEnv *env, jclass self, jlong conn) {
  uint64_t out = 0;
  zu_status st = p_conn_rows_read(H(zu_conn, conn), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_conn_rows_read", 0);
  return (jlong)out;
}

/* ---- the progress callback ---- */

/*
 * What a watcher is on this side: one global reference, which the
 * engine holds as its user_data and this shim frees when the connection
 * stops watching or closes. It is a heap cell rather than the reference
 * itself because a jobject is not a pointer on every JVM.
 */
typedef struct {
  jobject watcher;
} watch;

static int on_progress(void *user_data, uint64_t rows, uint64_t ms) {
  watch *w = (watch *)user_data;
  int attached = 0;
  JNIEnv *env = attach(&attached);
  jboolean go;

  if (env == NULL) {
    return 0; /* nothing can be asked, so stop rather than run on unwatched */
  }
  go = (*env)->CallBooleanMethod(env, w->watcher, m_progress_at, (jlong)rows, (jlong)ms);
  /*
   * Nothing may be thrown out of a callback the engine is inside of. A
   * watcher that threw is a program that has stopped wanting the
   * answer, so the exception is cleared and described, and the
   * statement is stopped, which is the reading that throws least away.
   */
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    go = JNI_FALSE;
  }
  detach(attached);
  return go == JNI_TRUE ? 1 : 0;
}

static jlong n_conn_set_progress(JNIEnv *env, jclass self, jlong conn, jobject watcher,
                                 jlong interval) {
  watch *w = NULL;
  zu_status st;
  (void)self;

  if (watcher != NULL) {
    w = (watch *)calloc(1, sizeof *w);
    if (w == NULL) {
      raise(env, ZU_ERROR, NULL, "zu_conn_set_progress");
      return 0;
    }
    w->watcher = (*env)->NewGlobalRef(env, watcher);
    if (w->watcher == NULL) {
      free(w);
      return 0;
    }
  }
  st = p_conn_set_progress(H(zu_conn, conn), w == NULL ? NULL : on_progress, w,
                           (uint64_t)interval);
  if (st != ZU_OK) {
    if (w != NULL) {
      (*env)->DeleteGlobalRef(env, w->watcher);
      free(w);
    }
    raise(env, st, NULL, "zu_conn_set_progress");
    return 0;
  }
  return A(w);
}

/* Says nothing will call this watcher again, which is what taking the
   arrangement back means. */
static void n_watch_free(JNIEnv *env, jclass self, jlong cookie) {
  watch *w = H(watch, cookie);
  (void)self;
  if (w != NULL) {
    (*env)->DeleteGlobalRef(env, w->watcher);
    free(w);
  }
}

static jboolean n_conn_in_transaction(JNIEnv *env, jclass self, jlong conn) {
  int32_t out = 0;
  zu_status st = p_conn_in_transaction(H(zu_conn, conn), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_conn_in_transaction", JNI_FALSE);
  return out != 0 ? JNI_TRUE : JNI_FALSE;
}

static void n_begin(JNIEnv *env, jclass self, jlong conn, jboolean read_only) {
  zu_error *e = NULL;
  zu_status st = p_begin(H(zu_conn, conn), read_only == JNI_TRUE ? 1 : 0, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_begin");
}

static void n_commit(JNIEnv *env, jclass self, jlong conn) {
  zu_error *e = NULL;
  zu_status st = p_commit(H(zu_conn, conn), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_commit");
}

static void n_rollback(JNIEnv *env, jclass self, jlong conn) {
  zu_error *e = NULL;
  zu_status st = p_rollback(H(zu_conn, conn), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_rollback");
}

static jlong n_query(JNIEnv *env, jclass self, jlong conn, jbyteArray statement) {
  zu_result *out = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed q;
  (void)self;

  if (!borrow(env, statement, &q)) {
    return 0;
  }
  st = p_query(H(zu_conn, conn), S(q), L(q), &out, &e);
  giveback(env, &q);
  FAIL_IF_V(env, st, e, "zu_query", 0);
  return A(out);
}

static jlong n_prepare(JNIEnv *env, jclass self, jlong conn, jbyteArray statement) {
  zu_stmt *out = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed q;
  (void)self;

  if (!borrow(env, statement, &q)) {
    return 0;
  }
  st = p_prepare(H(zu_conn, conn), S(q), L(q), &out, &e);
  giveback(env, &q);
  FAIL_IF_V(env, st, e, "zu_prepare", 0);
  return A(out);
}

static void n_bind_long(JNIEnv *env, jclass self, jlong stmt, jbyteArray name, jlong value) {
  borrowed n;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_bind_i64(H(zu_stmt, stmt), S(n), L(n), (int64_t)value);
  giveback(env, &n);
  FAIL_IF(env, st, NULL, "zu_bind_i64");
}

static void n_bind_double(JNIEnv *env, jclass self, jlong stmt, jbyteArray name, jdouble value) {
  borrowed n;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_bind_f64(H(zu_stmt, stmt), S(n), L(n), (double)value);
  giveback(env, &n);
  FAIL_IF(env, st, NULL, "zu_bind_f64");
}

static void n_bind_boolean(JNIEnv *env, jclass self, jlong stmt, jbyteArray name,
                           jboolean value) {
  borrowed n;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_bind_bool(H(zu_stmt, stmt), S(n), L(n), value == JNI_TRUE ? 1 : 0);
  giveback(env, &n);
  FAIL_IF(env, st, NULL, "zu_bind_bool");
}

static void n_bind_string(JNIEnv *env, jclass self, jlong stmt, jbyteArray name,
                          jbyteArray value) {
  borrowed n;
  borrowed v;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  if (!borrow(env, value, &v)) {
    giveback(env, &n);
    return;
  }
  st = p_bind_str(H(zu_stmt, stmt), S(n), L(n), S(v), L(v));
  giveback(env, &n);
  giveback(env, &v);
  FAIL_IF(env, st, NULL, "zu_bind_str");
}

static void n_bind_temporal(JNIEnv *env, jclass self, jlong stmt, jbyteArray name, jint kind,
                            jlong count, jint offset) {
  borrowed n;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_bind_temporal(H(zu_stmt, stmt), S(n), L(n), (int32_t)kind, (int64_t)count,
                       (int32_t)offset);
  giveback(env, &n);
  FAIL_IF(env, st, NULL, "zu_bind_temporal");
}

static void n_bind_null(JNIEnv *env, jclass self, jlong stmt, jbyteArray name) {
  borrowed n;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_bind_null(H(zu_stmt, stmt), S(n), L(n));
  giveback(env, &n);
  FAIL_IF(env, st, NULL, "zu_bind_null");
}

static jlong n_execute(JNIEnv *env, jclass self, jlong stmt) {
  zu_result *out = NULL;
  zu_error *e = NULL;
  zu_status st = p_execute(H(zu_stmt, stmt), &out, &e);
  (void)self;
  FAIL_IF_V(env, st, e, "zu_execute", 0);
  return A(out);
}

static void n_stmt_close(JNIEnv *env, jclass self, jlong stmt) {
  (void)env;
  (void)self;
  p_stmt_close(H(zu_stmt, stmt));
}

/* ---- results ---- */

static jlong n_result_rows(JNIEnv *env, jclass self, jlong result) {
  (void)env;
  (void)self;
  return (jlong)p_result_rows(H(zu_result, result));
}

static jint n_result_cols(JNIEnv *env, jclass self, jlong result) {
  (void)env;
  (void)self;
  return (jint)p_result_cols(H(zu_result, result));
}

static jbyteArray n_result_col_name(JNIEnv *env, jclass self, jlong result, jint col) {
  const char *p = NULL;
  size_t len = 0;
  zu_status st = p_result_col_name(H(zu_result, result), (uint32_t)col, &p, &len);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_result_col_name", NULL);
  return bytes(env, p, len);
}

static jint n_result_cell_type(JNIEnv *env, jclass self, jlong result, jlong row, jint col) {
  int32_t out = 0;
  zu_status st =
      p_result_cell_type(H(zu_result, result), (uint64_t)row, (uint32_t)col, &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_result_cell_type", 0);
  return (jint)out;
}

static jbyteArray n_result_cell_string(JNIEnv *env, jclass self, jlong result, jlong row,
                                       jint col) {
  const char *p = NULL;
  size_t len = 0;
  zu_status st =
      p_result_cell_str(H(zu_result, result), (uint64_t)row, (uint32_t)col, &p, &len);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_result_cell_str", NULL);
  return bytes(env, p, len);
}

static jbyteArray n_result_gqlstatus(JNIEnv *env, jclass self, jlong result) {
  size_t len = 0;
  const char *p = p_result_gqlstatus(H(zu_result, result), &len);
  (void)self;
  return bytes(env, p, len);
}

static jint n_result_notices(JNIEnv *env, jclass self, jlong result) {
  (void)env;
  (void)self;
  return (jint)p_result_notices(H(zu_result, result));
}

static jobject n_result_notice(JNIEnv *env, jclass self, jlong result, jint index) {
  zu_error *e = NULL;
  zu_status st = p_result_notice(H(zu_result, result), (uint32_t)index, &e);
  (void)self;
  if (st == ZU_DONE) {
    return NULL;
  }
  FAIL_IF_V(env, st, NULL, "zu_result_notice", NULL);
  return e == NULL ? NULL : record(env, e);
}

static void n_result_free(JNIEnv *env, jclass self, jlong result) {
  (void)env;
  (void)self;
  p_result_free(H(zu_result, result));
}

/*
 * A column, as a buffer over the engine's own memory. Nothing is copied
 * here and nothing is copied on the Java side either: what comes back
 * is a window onto the result, good until zu_result_free, which is the
 * rule the API module holds callers to.
 *
 * A null buffer is not a failure. It is the answer for a column the
 * result built a row at a time, which has no run of values to point at,
 * and the API module falls back to reading it cell by cell.
 */
static jobject window(JNIEnv *env, const void *p, jlong rows, jlong width) {
  if (p == NULL) {
    return NULL;
  }
  return (*env)->NewDirectByteBuffer(env, (void *)(intptr_t)p, rows * width);
}

#define COLUMN(fn, sym, what, type, width)                                          \
  static jobject fn(JNIEnv *env, jclass self, jlong result, jint col, jlong rows) {  \
    const type *p = NULL;                                                            \
    zu_status st = sym(H(zu_result, result), (uint32_t)col, &p);                     \
    (void)self;                                                                      \
    if (st == ZU_DONE) {                                                             \
      return NULL;                                                                   \
    }                                                                                \
    FAIL_IF_V(env, st, NULL, what, NULL);                                            \
    return window(env, p, rows, width);                                              \
  }

COLUMN(n_col_longs, p_result_col_i64, "zu_result_col_i64", int64_t, 8)
COLUMN(n_col_doubles, p_result_col_f64, "zu_result_col_f64", double, 8)
COLUMN(n_col_node_offsets, p_result_col_node_offset, "zu_result_col_node_offset", uint64_t, 8)
COLUMN(n_col_valid, p_result_col_valid, "zu_result_col_valid", uint8_t, 1)

#define CHUNK_COLUMN(fn, sym, what, type, width)                                       \
  static jobject fn(JNIEnv *env, jclass self, jlong result, jlong chunk, jint col,      \
                    jlong rows) {                                                       \
    const type *p = NULL;                                                               \
    zu_status st = sym(H(zu_result, result), (uint64_t)chunk, (uint32_t)col, &p);       \
    (void)self;                                                                         \
    if (st == ZU_DONE) {                                                                \
      return NULL;                                                                      \
    }                                                                                   \
    FAIL_IF_V(env, st, NULL, what, NULL);                                               \
    return window(env, p, rows, width);                                                 \
  }

CHUNK_COLUMN(n_chunk_longs, p_result_chunk_col_i64, "zu_result_chunk_col_i64", int64_t, 8)
CHUNK_COLUMN(n_chunk_doubles, p_result_chunk_col_f64, "zu_result_chunk_col_f64", double, 8)
CHUNK_COLUMN(n_chunk_node_offsets, p_result_chunk_col_node_offset,
             "zu_result_chunk_col_node_offset", uint64_t, 8)
CHUNK_COLUMN(n_chunk_valid, p_result_chunk_col_valid, "zu_result_chunk_col_valid", uint8_t, 1)

static jlong n_chunk_count(JNIEnv *env, jclass self, jlong result) {
  (void)env;
  (void)self;
  return (jlong)p_result_chunk_count(H(zu_result, result));
}

static jlongArray n_chunk(JNIEnv *env, jclass self, jlong result, jlong chunk) {
  uint64_t offset = 0;
  uint64_t rows = 0;
  jlong out[2];
  zu_status st = p_result_chunk(H(zu_result, result), (uint64_t)chunk, &offset, &rows);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_result_chunk", NULL);
  out[0] = (jlong)offset;
  out[1] = (jlong)rows;
  return longs(env, out, 2);
}

/*
 * The whole result to Arrow, which is the one call that spends what it
 * is given. The result is nulled by the engine on every path, including
 * the refusal, so the Java side has already let go of the handle before
 * this runs and there is nothing here to put back.
 */
static void n_result_arrow(JNIEnv *env, jclass self, jlong conn, jlong result,
                           jlong rows_per_batch, jlong stream) {
  zu_result *r = H(zu_result, result);
  zu_error *e = NULL;
  zu_status st = p_result_arrow(H(zu_conn, conn), &r, (uint64_t)rows_per_batch,
                                (struct ArrowArrayStream *)(intptr_t)stream, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_result_arrow");
}

/* ---- values ---- */

static jlong n_result_cell(JNIEnv *env, jclass self, jlong result, jlong row, jint col) {
  const zu_value *out = NULL;
  zu_status st = p_result_cell(H(zu_result, result), (uint64_t)row, (uint32_t)col, &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_result_cell", 0);
  return A(out);
}

static jint n_value_type(JNIEnv *env, jclass self, jlong value) {
  (void)env;
  (void)self;
  return (jint)p_value_type(H(zu_value, value));
}

static jboolean n_value_boolean(JNIEnv *env, jclass self, jlong value) {
  int32_t out = 0;
  zu_status st = p_value_bool(H(zu_value, value), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_bool", JNI_FALSE);
  return out != 0 ? JNI_TRUE : JNI_FALSE;
}

static jlong n_value_long(JNIEnv *env, jclass self, jlong value) {
  int64_t out = 0;
  zu_status st = p_value_i64(H(zu_value, value), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_i64", 0);
  return (jlong)out;
}

static jdouble n_value_double(JNIEnv *env, jclass self, jlong value) {
  double out = 0;
  zu_status st = p_value_f64(H(zu_value, value), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_f64", 0);
  return (jdouble)out;
}

static jbyteArray n_value_string(JNIEnv *env, jclass self, jlong value) {
  const char *p = NULL;
  size_t len = 0;
  zu_status st = p_value_str(H(zu_value, value), &p, &len);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_str", NULL);
  return bytes(env, p, len);
}

static jlongArray n_value_temporal(JNIEnv *env, jclass self, jlong value) {
  int32_t kind = 0;
  int64_t count = 0;
  int32_t offset = 0;
  jlong out[3];
  zu_status st = p_value_temporal(H(zu_value, value), &kind, &count, &offset);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_temporal", NULL);
  out[0] = (jlong)kind;
  out[1] = (jlong)count;
  out[2] = (jlong)offset;
  return longs(env, out, 3);
}

static jlongArray n_value_node(JNIEnv *env, jclass self, jlong value) {
  uint32_t table = 0;
  uint64_t offset = 0;
  jlong out[2];
  zu_status st = p_value_node(H(zu_value, value), &table, &offset);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_node", NULL);
  out[0] = (jlong)table;
  out[1] = (jlong)offset;
  return longs(env, out, 2);
}

static jlongArray n_value_rel(JNIEnv *env, jclass self, jlong value) {
  uint32_t table = 0;
  uint64_t src = 0;
  uint64_t dst = 0;
  jlong out[3];
  zu_status st = p_value_rel(H(zu_value, value), &table, &src, &dst);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_rel", NULL);
  out[0] = (jlong)table;
  out[1] = (jlong)src;
  out[2] = (jlong)dst;
  return longs(env, out, 3);
}

static jlong n_value_length(JNIEnv *env, jclass self, jlong value) {
  (void)env;
  (void)self;
  return (jlong)p_value_len(H(zu_value, value));
}

static jlong n_value_at(JNIEnv *env, jclass self, jlong value, jlong index) {
  const zu_value *out = NULL;
  zu_status st = p_value_at(H(zu_value, value), (uint64_t)index, &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_at", 0);
  return A(out);
}

static jbyteArray n_value_field(JNIEnv *env, jclass self, jlong value, jlong index) {
  const char *p = NULL;
  size_t len = 0;
  zu_status st = p_value_field(H(zu_value, value), (uint64_t)index, &p, &len);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_value_field", NULL);
  return bytes(env, p, len);
}

/* ---- the loader ---- */

static jlong n_loader_create(JNIEnv *env, jclass self, jbyteArray path) {
  zu_loader *out = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed p;
  (void)self;

  if (!borrow(env, path, &p)) {
    return 0;
  }
  st = p_loader_create(S(p), L(p), &out, &e);
  giveback(env, &p);
  FAIL_IF_V(env, st, e, "zu_loader_create", 0);
  return A(out);
}

static void n_loader_table(JNIEnv *env, jclass self, jlong loader, jbyteArray nodes,
                           jbyteArray edges, jlong rows) {
  borrowed n;
  borrowed g;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, nodes, &n)) {
    return;
  }
  if (!borrow(env, edges, &g)) {
    giveback(env, &n);
    return;
  }
  st = p_loader_table(H(zu_loader, loader), S(n), L(n), S(g), L(g), (uint64_t)rows, &e);
  giveback(env, &n);
  giveback(env, &g);
  FAIL_IF(env, st, e, "zu_loader_table");
}

static void n_loader_edges(JNIEnv *env, jclass self, jlong loader, jobject from, jobject to,
                           jlong count) {
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  st = p_loader_edges(H(zu_loader, loader), (const uint32_t *)address(env, from),
                      (const uint32_t *)address(env, to), (uint64_t)count, &e);
  FAIL_IF(env, st, e, "zu_loader_edges");
}

#define LOADER_COLUMN(fn, sym, what, type)                                                \
  static void fn(JNIEnv *env, jclass self, jlong loader, jbyteArray name, jobject values, \
                 jlong count) {                                                           \
    borrowed n;                                                                           \
    zu_error *e = NULL;                                                                   \
    zu_status st;                                                                         \
    (void)self;                                                                           \
    if (!borrow(env, name, &n)) {                                                         \
      return;                                                                             \
    }                                                                                     \
    st = sym(H(zu_loader, loader), S(n), L(n), (const type *)address(env, values),        \
             (uint64_t)count, &e);                                                        \
    giveback(env, &n);                                                                    \
    FAIL_IF(env, st, e, what);                                                            \
  }

LOADER_COLUMN(n_loader_col_longs, p_loader_col_i64, "zu_loader_col_i64", int64_t)
LOADER_COLUMN(n_loader_col_doubles, p_loader_col_f64, "zu_loader_col_f64", double)
LOADER_COLUMN(n_loader_col_booleans, p_loader_col_bool, "zu_loader_col_bool", int32_t)

static void n_loader_col_temporal(JNIEnv *env, jclass self, jlong loader, jbyteArray name,
                                  jint kind, jobject values, jlong count) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_loader_col_temporal(H(zu_loader, loader), S(n), L(n), (int32_t)kind,
                             (const int64_t *)address(env, values), (uint64_t)count, &e);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_loader_col_temporal");
}

/*
 * A column of strings, which is the one call that has to gather. The
 * values arrive as one array of arrays, already encoded on the Java
 * side, and are laid out here as the two parallel arrays the ABI takes.
 * Everything is freed before this returns: the engine copies what it
 * keeps.
 */
static void n_loader_col_strings(JNIEnv *env, jclass self, jlong loader, jbyteArray name,
                                 jobjectArray values) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  jsize count = (*env)->GetArrayLength(env, values);
  const char **pointers = NULL;
  size_t *lengths = NULL;
  jsize i;
  jsize made = 0;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  pointers = (const char **)calloc((size_t)count + 1, sizeof *pointers);
  lengths = (size_t *)calloc((size_t)count + 1, sizeof *lengths);
  if (pointers == NULL || lengths == NULL) {
    free(pointers);
    free((void *)lengths);
    giveback(env, &n);
    raise(env, ZU_ERROR, NULL, "zu_loader_col_str");
    return;
  }
  for (i = 0; i < count; i++) {
    jbyteArray one = (jbyteArray)(*env)->GetObjectArrayElement(env, values, i);
    jsize len = (*env)->GetArrayLength(env, one);
    char *copy = (char *)malloc((size_t)len + 1);
    if (copy == NULL) {
      break;
    }
    (*env)->GetByteArrayRegion(env, one, 0, len, (jbyte *)copy);
    copy[len] = '\0';
    pointers[i] = copy;
    lengths[i] = (size_t)len;
    made++;
    (*env)->DeleteLocalRef(env, one);
  }
  if (made == count) {
    st = p_loader_col_str(H(zu_loader, loader), S(n), L(n), pointers, lengths,
                          (uint64_t)count, &e);
  } else {
    st = ZU_ERROR;
  }
  for (i = 0; i < made; i++) {
    free((void *)pointers[i]);
  }
  free(pointers);
  free(lengths);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_loader_col_str");
}

static void n_loader_finish(JNIEnv *env, jclass self, jlong loader) {
  zu_error *e = NULL;
  zu_status st = p_loader_finish(H(zu_loader, loader), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_loader_finish");
}

static void n_loader_free(JNIEnv *env, jclass self, jlong loader) {
  (void)env;
  (void)self;
  p_loader_free(H(zu_loader, loader));
}

/* ---- the appender ---- */

static jlong n_appender_open(JNIEnv *env, jclass self, jlong conn, jbyteArray table) {
  zu_appender *out = NULL;
  zu_error *e = NULL;
  zu_status st;
  borrowed t;
  (void)self;

  if (!borrow(env, table, &t)) {
    return 0;
  }
  st = p_appender_open(H(zu_conn, conn), S(t), L(t), &out, &e);
  giveback(env, &t);
  FAIL_IF_V(env, st, e, "zu_appender_open", 0);
  return A(out);
}

static void n_append_boolean(JNIEnv *env, jclass self, jlong appender, jboolean value) {
  zu_error *e = NULL;
  zu_status st =
      p_append_bool(H(zu_appender, appender), value == JNI_TRUE ? 1 : 0, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_bool");
}

static void n_append_long(JNIEnv *env, jclass self, jlong appender, jlong value) {
  zu_error *e = NULL;
  zu_status st = p_append_i64(H(zu_appender, appender), (int64_t)value, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_i64");
}

static void n_append_double(JNIEnv *env, jclass self, jlong appender, jdouble value) {
  zu_error *e = NULL;
  zu_status st = p_append_f64(H(zu_appender, appender), (double)value, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_f64");
}

static void n_append_string(JNIEnv *env, jclass self, jlong appender, jbyteArray value) {
  borrowed v;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, value, &v)) {
    return;
  }
  st = p_append_str(H(zu_appender, appender), S(v), L(v), &e);
  giveback(env, &v);
  FAIL_IF(env, st, e, "zu_append_str");
}

static void n_append_bytes(JNIEnv *env, jclass self, jlong appender, jobject value,
                           jlong length) {
  zu_error *e = NULL;
  zu_status st = p_append_bytes(H(zu_appender, appender),
                                (const uint8_t *)address(env, value), (size_t)length, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_bytes");
}

static void n_append_temporal(JNIEnv *env, jclass self, jlong appender, jint kind,
                              jlong count) {
  zu_error *e = NULL;
  zu_status st =
      p_append_temporal(H(zu_appender, appender), (int32_t)kind, (int64_t)count, &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_temporal");
}

static void n_append_end_row(JNIEnv *env, jclass self, jlong appender) {
  zu_error *e = NULL;
  zu_status st = p_append_end_row(H(zu_appender, appender), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_append_end_row");
}

static void n_appender_flush(JNIEnv *env, jclass self, jlong appender) {
  zu_error *e = NULL;
  zu_status st = p_appender_flush(H(zu_appender, appender), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_appender_flush");
}

static jlong n_appender_buffered(JNIEnv *env, jclass self, jlong appender) {
  uint64_t out = 0;
  zu_status st = p_appender_buffered(H(zu_appender, appender), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_appender_buffered", 0);
  return (jlong)out;
}

static jlong n_appender_committed(JNIEnv *env, jclass self, jlong appender) {
  uint64_t out = 0;
  zu_status st = p_appender_committed(H(zu_appender, appender), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_appender_committed", 0);
  return (jlong)out;
}

static jint n_appender_columns(JNIEnv *env, jclass self, jlong appender) {
  uint32_t out = 0;
  zu_status st = p_appender_cols(H(zu_appender, appender), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_appender_cols", 0);
  return (jint)out;
}

static jbyteArray n_appender_column_name(JNIEnv *env, jclass self, jlong appender, jint col) {
  size_t len = 0;
  const char *p = p_appender_col_name(H(zu_appender, appender), (uint32_t)col, &len);
  (void)self;
  return bytes(env, p, len);
}

static jlong n_appender_discard(JNIEnv *env, jclass self, jlong appender) {
  uint64_t out = 0;
  zu_status st = p_appender_discard(H(zu_appender, appender), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_appender_discard", 0);
  return (jlong)out;
}

static jlong n_appender_close(JNIEnv *env, jclass self, jlong appender) {
  uint64_t out = 0;
  zu_error *e = NULL;
  zu_status st = p_appender_close(H(zu_appender, appender), &out, &e);
  (void)self;
  FAIL_IF_V(env, st, e, "zu_appender_close", 0);
  return (jlong)out;
}

static void n_appender_free(JNIEnv *env, jclass self, jlong appender) {
  (void)env;
  (void)self;
  p_appender_free(H(zu_appender, appender));
}

/* ---- frames ---- */

/*
 * The release callback, which is how a host learns the engine has
 * finished with the buffers it lent. It runs once, on a thread of the
 * library's, after the last statement reading the frame ends.
 *
 * Nothing may be thrown out of here either, and for the same reason.
 */
static void on_release(void *owner) {
  jobject runnable = (jobject)owner;
  int attached = 0;
  JNIEnv *env;

  if (runnable == NULL) {
    return;
  }
  env = attach(&attached);
  if (env == NULL) {
    return;
  }
  (*env)->CallVoidMethod(env, runnable, m_runnable_run);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
  }
  (*env)->DeleteGlobalRef(env, runnable);
  detach(attached);
}

static jlong n_frame_new(JNIEnv *env, jclass self, jbyteArray name, jlong rows,
                         jobject release) {
  borrowed n;
  zu_frame *out = NULL;
  zu_error *e = NULL;
  zu_status st;
  jobject owner = NULL;
  (void)self;

  if (!borrow(env, name, &n)) {
    return 0;
  }
  if (release != NULL) {
    owner = (*env)->NewGlobalRef(env, release);
    if (owner == NULL) {
      giveback(env, &n);
      return 0;
    }
  }
  st = p_frame_new(S(n), L(n), (uint64_t)rows, owner, owner == NULL ? NULL : on_release, &out,
                   &e);
  giveback(env, &n);
  if (st != ZU_OK) {
    /* The frame was never made, so nothing will ever call the release,
       and the reference this side took has to go back here. */
    if (owner != NULL) {
      (*env)->DeleteGlobalRef(env, owner);
    }
    raise(env, st, e, "zu_frame_new");
    return 0;
  }
  return A(out);
}

static void n_frame_col_int(JNIEnv *env, jclass self, jlong frame, jbyteArray name,
                            jobject values, jlong count, jint bits, jboolean is_signed,
                            jlong scale, jint temporal) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_frame_col_int(H(zu_frame, frame), S(n), L(n), address(env, values), (uint64_t)count,
                       (int32_t)bits, is_signed == JNI_TRUE ? 1 : 0, (int64_t)scale,
                       (int32_t)temporal, &e);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_frame_col_int");
}

static void n_frame_col_float(JNIEnv *env, jclass self, jlong frame, jbyteArray name,
                              jobject values, jlong count, jint bits) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_frame_col_float(H(zu_frame, frame), S(n), L(n), address(env, values),
                         (uint64_t)count, (int32_t)bits, &e);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_frame_col_float");
}

static void n_frame_col_bool(JNIEnv *env, jclass self, jlong frame, jbyteArray name,
                             jobject bitmap, jlong count) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_frame_col_bool(H(zu_frame, frame), S(n), L(n), address(env, bitmap), (uint64_t)count,
                        &e);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_frame_col_bool");
}

static void n_frame_col_str(JNIEnv *env, jclass self, jlong frame, jbyteArray name,
                            jobject offsets, jboolean wide, jobject data, jlong data_length,
                            jlong count) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  st = p_frame_col_str(H(zu_frame, frame), S(n), L(n), address(env, offsets),
                       wide == JNI_TRUE ? 1 : 0, address(env, data), (size_t)data_length,
                       (uint64_t)count, &e);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_frame_col_str");
}

static void n_frame_col_view(JNIEnv *env, jclass self, jlong frame, jbyteArray name,
                             jobject views, jobjectArray data, jlongArray data_lengths,
                             jlong count) {
  borrowed n;
  zu_error *e = NULL;
  zu_status st;
  jsize buffers = (*env)->GetArrayLength(env, data);
  const void **pointers;
  size_t *lengths;
  jlong *given;
  jsize i;
  (void)self;

  if (!borrow(env, name, &n)) {
    return;
  }
  pointers = (const void **)calloc((size_t)buffers + 1, sizeof *pointers);
  lengths = (size_t *)calloc((size_t)buffers + 1, sizeof *lengths);
  if (pointers == NULL || lengths == NULL) {
    free(pointers);
    free(lengths);
    giveback(env, &n);
    raise(env, ZU_ERROR, NULL, "zu_frame_col_view");
    return;
  }
  given = (*env)->GetLongArrayElements(env, data_lengths, NULL);
  for (i = 0; i < buffers; i++) {
    jobject one = (*env)->GetObjectArrayElement(env, data, i);
    pointers[i] = address(env, one);
    lengths[i] = given == NULL ? 0 : (size_t)given[i];
    (*env)->DeleteLocalRef(env, one);
  }
  if (given != NULL) {
    (*env)->ReleaseLongArrayElements(env, data_lengths, given, JNI_ABORT);
  }
  st = p_frame_col_view(H(zu_frame, frame), S(n), L(n), address(env, views), pointers,
                        lengths, (size_t)buffers, (uint64_t)count, &e);
  free(pointers);
  free(lengths);
  giveback(env, &n);
  FAIL_IF(env, st, e, "zu_frame_col_view");
}

static void n_frame_free(JNIEnv *env, jclass self, jlong frame) {
  (void)env;
  (void)self;
  p_frame_free(H(zu_frame, frame));
}

static void n_conn_register(JNIEnv *env, jclass self, jlong conn, jlong frame) {
  zu_error *e = NULL;
  zu_status st = p_conn_register(H(zu_conn, conn), H(zu_frame, frame), &e);
  (void)self;
  FAIL_IF(env, st, e, "zu_conn_register");
}

static jboolean n_conn_unregister(JNIEnv *env, jclass self, jlong conn, jbyteArray name) {
  borrowed n;
  int32_t out = 0;
  zu_error *e = NULL;
  zu_status st;
  (void)self;

  if (!borrow(env, name, &n)) {
    return JNI_FALSE;
  }
  st = p_conn_unregister(H(zu_conn, conn), S(n), L(n), &out, &e);
  giveback(env, &n);
  FAIL_IF_V(env, st, e, "zu_conn_unregister", JNI_FALSE);
  return out != 0 ? JNI_TRUE : JNI_FALSE;
}

static jlong n_conn_registered_count(JNIEnv *env, jclass self, jlong conn) {
  uint64_t out = 0;
  zu_status st = p_conn_registered_count(H(zu_conn, conn), &out);
  (void)self;
  FAIL_IF_V(env, st, NULL, "zu_conn_registered_count", 0);
  return (jlong)out;
}

static jbyteArray n_conn_registered_name(JNIEnv *env, jclass self, jlong conn, jlong index) {
  size_t len = 0;
  const char *p = p_conn_registered_name(H(zu_conn, conn), (uint64_t)index, &len);
  (void)self;
  return bytes(env, p, len);
}

/* ------------------------------------------------------------------ */
/* binding                                                            */
/* ------------------------------------------------------------------ */

static const JNINativeMethod methods[] = {
    {"nLoad", "([B)[B", (void *)n_load},
    {"nVersion", "()[B", (void *)n_version},
    {"nConfigSet", "(JJZ[B[B)[J", (void *)n_config_set},
    {"nDatabaseOpen", "([BJJZ)J", (void *)n_database_open},
    {"nDatabaseCreate", "([BJJZ)J", (void *)n_database_create},
    {"nDatabaseMemory", "(JJZ)J", (void *)n_database_memory},
    {"nDatabaseIsMemory", "(J)Z", (void *)n_database_is_memory},
    {"nDatabasePath", "(J)[B", (void *)n_database_path},
    {"nDatabaseClose", "(J)V", (void *)n_database_close},
    {"nConnect", "(J)J", (void *)n_connect},
    {"nOpen", "([B)J", (void *)n_open},
    {"nCreate", "([B)J", (void *)n_create},
    {"nMemory", "()J", (void *)n_memory},
    {"nConnDuplicate", "(J)J", (void *)n_conn_duplicate},
    {"nConnClose", "(J)V", (void *)n_conn_close},
    {"nConnInterrupt", "(J)V", (void *)n_conn_interrupt},
    {"nConnRowsRead", "(J)J", (void *)n_conn_rows_read},
    {"nConnSetProgress", "(JLdev/zudb/Progress;J)J", (void *)n_conn_set_progress},
    {"nWatchFree", "(J)V", (void *)n_watch_free},
    {"nConnInTransaction", "(J)Z", (void *)n_conn_in_transaction},
    {"nBegin", "(JZ)V", (void *)n_begin},
    {"nCommit", "(J)V", (void *)n_commit},
    {"nRollback", "(J)V", (void *)n_rollback},
    {"nQuery", "(J[B)J", (void *)n_query},
    {"nPrepare", "(J[B)J", (void *)n_prepare},
    {"nBindLong", "(J[BJ)V", (void *)n_bind_long},
    {"nBindDouble", "(J[BD)V", (void *)n_bind_double},
    {"nBindBoolean", "(J[BZ)V", (void *)n_bind_boolean},
    {"nBindString", "(J[B[B)V", (void *)n_bind_string},
    {"nBindTemporal", "(J[BIJI)V", (void *)n_bind_temporal},
    {"nBindNull", "(J[B)V", (void *)n_bind_null},
    {"nExecute", "(J)J", (void *)n_execute},
    {"nStmtClose", "(J)V", (void *)n_stmt_close},
    {"nResultRows", "(J)J", (void *)n_result_rows},
    {"nResultCols", "(J)I", (void *)n_result_cols},
    {"nResultColName", "(JI)[B", (void *)n_result_col_name},
    {"nResultCellType", "(JJI)I", (void *)n_result_cell_type},
    {"nResultCellString", "(JJI)[B", (void *)n_result_cell_string},
    {"nResultGqlstatus", "(J)[B", (void *)n_result_gqlstatus},
    {"nResultNotices", "(J)I", (void *)n_result_notices},
    {"nResultNotice", "(JI)Ldev/zudb/Diagnostic;", (void *)n_result_notice},
    {"nResultFree", "(J)V", (void *)n_result_free},
    {"nColLongs", "(JIJ)Ljava/nio/ByteBuffer;", (void *)n_col_longs},
    {"nColDoubles", "(JIJ)Ljava/nio/ByteBuffer;", (void *)n_col_doubles},
    {"nColNodeOffsets", "(JIJ)Ljava/nio/ByteBuffer;", (void *)n_col_node_offsets},
    {"nColValid", "(JIJ)Ljava/nio/ByteBuffer;", (void *)n_col_valid},
    {"nChunkCount", "(J)J", (void *)n_chunk_count},
    {"nChunk", "(JJ)[J", (void *)n_chunk},
    {"nChunkLongs", "(JJIJ)Ljava/nio/ByteBuffer;", (void *)n_chunk_longs},
    {"nChunkDoubles", "(JJIJ)Ljava/nio/ByteBuffer;", (void *)n_chunk_doubles},
    {"nChunkNodeOffsets", "(JJIJ)Ljava/nio/ByteBuffer;", (void *)n_chunk_node_offsets},
    {"nChunkValid", "(JJIJ)Ljava/nio/ByteBuffer;", (void *)n_chunk_valid},
    {"nResultArrow", "(JJJJ)V", (void *)n_result_arrow},
    {"nResultCell", "(JJI)J", (void *)n_result_cell},
    {"nValueType", "(J)I", (void *)n_value_type},
    {"nValueBoolean", "(J)Z", (void *)n_value_boolean},
    {"nValueLong", "(J)J", (void *)n_value_long},
    {"nValueDouble", "(J)D", (void *)n_value_double},
    {"nValueString", "(J)[B", (void *)n_value_string},
    {"nValueTemporal", "(J)[J", (void *)n_value_temporal},
    {"nValueNode", "(J)[J", (void *)n_value_node},
    {"nValueRel", "(J)[J", (void *)n_value_rel},
    {"nValueLength", "(J)J", (void *)n_value_length},
    {"nValueAt", "(JJ)J", (void *)n_value_at},
    {"nValueField", "(JJ)[B", (void *)n_value_field},
    {"nLoaderCreate", "([B)J", (void *)n_loader_create},
    {"nLoaderTable", "(J[B[BJ)V", (void *)n_loader_table},
    {"nLoaderEdges", "(JLjava/nio/Buffer;Ljava/nio/Buffer;J)V", (void *)n_loader_edges},
    {"nLoaderColLongs", "(J[BLjava/nio/Buffer;J)V", (void *)n_loader_col_longs},
    {"nLoaderColDoubles", "(J[BLjava/nio/Buffer;J)V", (void *)n_loader_col_doubles},
    {"nLoaderColBooleans", "(J[BLjava/nio/Buffer;J)V", (void *)n_loader_col_booleans},
    {"nLoaderColStrings", "(J[B[[B)V", (void *)n_loader_col_strings},
    {"nLoaderColTemporal", "(J[BILjava/nio/Buffer;J)V", (void *)n_loader_col_temporal},
    {"nLoaderFinish", "(J)V", (void *)n_loader_finish},
    {"nLoaderFree", "(J)V", (void *)n_loader_free},
    {"nAppenderOpen", "(J[B)J", (void *)n_appender_open},
    {"nAppendBoolean", "(JZ)V", (void *)n_append_boolean},
    {"nAppendLong", "(JJ)V", (void *)n_append_long},
    {"nAppendDouble", "(JD)V", (void *)n_append_double},
    {"nAppendString", "(J[B)V", (void *)n_append_string},
    {"nAppendBytes", "(JLjava/nio/Buffer;J)V", (void *)n_append_bytes},
    {"nAppendTemporal", "(JIJ)V", (void *)n_append_temporal},
    {"nAppendEndRow", "(J)V", (void *)n_append_end_row},
    {"nAppenderFlush", "(J)V", (void *)n_appender_flush},
    {"nAppenderBuffered", "(J)J", (void *)n_appender_buffered},
    {"nAppenderCommitted", "(J)J", (void *)n_appender_committed},
    {"nAppenderColumns", "(J)I", (void *)n_appender_columns},
    {"nAppenderColumnName", "(JI)[B", (void *)n_appender_column_name},
    {"nAppenderDiscard", "(J)J", (void *)n_appender_discard},
    {"nAppenderClose", "(J)J", (void *)n_appender_close},
    {"nAppenderFree", "(J)V", (void *)n_appender_free},
    {"nFrameNew", "([BJLjava/lang/Runnable;)J", (void *)n_frame_new},
    {"nFrameColInt", "(J[BLjava/nio/Buffer;JIZJI)V", (void *)n_frame_col_int},
    {"nFrameColFloat", "(J[BLjava/nio/Buffer;JI)V", (void *)n_frame_col_float},
    {"nFrameColBool", "(J[BLjava/nio/Buffer;J)V", (void *)n_frame_col_bool},
    {"nFrameColStr", "(J[BLjava/nio/Buffer;ZLjava/nio/Buffer;JJ)V", (void *)n_frame_col_str},
    {"nFrameColView", "(J[BLjava/nio/Buffer;[Ljava/nio/Buffer;[JJ)V",
     (void *)n_frame_col_view},
    {"nFrameFree", "(J)V", (void *)n_frame_free},
    {"nConnRegister", "(JJ)V", (void *)n_conn_register},
    {"nConnUnregister", "(J[B)Z", (void *)n_conn_unregister},
    {"nConnRegisteredCount", "(J)J", (void *)n_conn_registered_count},
    {"nConnRegisteredName", "(JJ)[B", (void *)n_conn_registered_name},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
  (void)reserved;
  vm = jvm;
  return JNI_VERSION_1_8;
}

/*
 * The one method bound by name, which binds the rest.
 *
 * It is called from the static initialiser of JniBinding, so the class
 * it is handed is the one to register against and the loader it can
 * find dev.zudb classes through is the right one. Doing this in
 * JNI_OnLoad instead would mean FindClass against the system loader,
 * which does not see a client on the module path or under an
 * application server's loader of its own.
 */
JNIEXPORT jboolean JNICALL Java_dev_zudb_jni_JniBinding_nRegister(JNIEnv *env, jclass self) {
  jclass diagnostic;
  jclass progress;
  jclass runnable;

  diagnostic = (*env)->FindClass(env, "dev/zudb/Diagnostic");
  if (diagnostic == NULL) {
    return JNI_FALSE;
  }
  progress = (*env)->FindClass(env, "dev/zudb/Progress");
  if (progress == NULL) {
    return JNI_FALSE;
  }
  runnable = (*env)->FindClass(env, "java/lang/Runnable");
  if (runnable == NULL) {
    return JNI_FALSE;
  }

  m_diagnostic = (*env)->GetStaticMethodID(env, self, "diagnostic",
                                           "(I[B[B[BIIII[B[BZ)Ldev/zudb/Diagnostic;");
  m_misuse =
      (*env)->GetStaticMethodID(env, self, "misuse", "(ILjava/lang/String;)Ldev/zudb/ZuException;");
  m_to_exception =
      (*env)->GetMethodID(env, diagnostic, "toException", "()Ldev/zudb/ZuException;");
  m_progress_at = (*env)->GetMethodID(env, progress, "at", "(JJ)Z");
  m_runnable_run = (*env)->GetMethodID(env, runnable, "run", "()V");
  if (m_diagnostic == NULL || m_misuse == NULL || m_to_exception == NULL ||
      m_progress_at == NULL || m_runnable_run == NULL) {
    return JNI_FALSE;
  }

  c_binding = (*env)->NewGlobalRef(env, self);
  if (c_binding == NULL) {
    return JNI_FALSE;
  }

  return (*env)->RegisterNatives(env, self, methods,
                                 (jint)(sizeof methods / sizeof methods[0])) == 0
             ? JNI_TRUE
             : JNI_FALSE;
}
