package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A path and a configuration that have been checked against a real file.
 *
 * <p>It holds no descriptor and no cache, so it is safe to share between
 * threads and cheap to keep. What cannot be shared is a {@link Connection}: a
 * program that queries from four threads opens one of these and connects four
 * times.
 *
 * <p>The file is opened once here and closed again, so a path that is not a
 * zu database fails at {@link #open} rather than on the first query. Closing
 * a database does not close the connections opened from it, because each one
 * holds its own file handle; this releases the path and the configuration and
 * nothing else.
 *
 * <pre>{@code
 * try (Database db = Database.open(Path.of("social.zu1"));
 *      Connection conn = db.connect()) {
 *     ...
 * }
 * }</pre>
 */
public final class Database implements AutoCloseable {

  private final ZuBinding zu;
  private final AtomicLong handle;

  private Database(ZuBinding zu, long handle) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
  }

  /**
   * Opens an existing database with the default configuration.
   *
   * @param path the file
   * @return the database
   */
  public static Database open(Path path) {
    return open(path, Config.defaults());
  }

  /**
   * Opens an existing database.
   *
   * @param path the file
   * @param config the caches, the workers and whether writes are refused
   * @return the database
   */
  public static Database open(Path path, Config config) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(config, "config");
    ZuBinding zu = Zu.binding();
    return new Database(
        zu,
        zu.databaseOpen(
            path.toString(), config.memoryLimit(), config.threads(), config.readOnly()));
  }

  /**
   * Opens an existing database named by a string, for the caller who has one
   * and does not want to write {@code Path.of} around it.
   *
   * @param path the file
   * @return the database
   */
  public static Database open(String path) {
    return open(Path.of(Objects.requireNonNull(path, "path")), Config.defaults());
  }

  /**
   * Opens an existing database named by a string.
   *
   * @param path the file
   * @param config the caches, the workers and whether writes are refused
   * @return the database
   */
  public static Database open(String path, Config config) {
    return open(Path.of(Objects.requireNonNull(path, "path")), config);
  }

  /**
   * Creates a database and opens it.
   *
   * <p>The path must not exist. A create that opened what it found there
   * would be the call that quietly writes into somebody else's data, and a
   * program that wants either one has {@link #open} to fall back to and a
   * decision to make about which.
   *
   * @param path the file to make
   * @return the database
   */
  public static Database create(Path path) {
    return create(path, Config.defaults());
  }

  /**
   * Creates a database and opens it.
   *
   * @param path the file to make, which must not exist
   * @param config the caches and the workers
   * @return the database
   */
  public static Database create(Path path, Config config) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(config, "config");
    ZuBinding zu = Zu.binding();
    return new Database(
        zu,
        zu.databaseCreate(
            path.toString(), config.memoryLimit(), config.threads(), config.readOnly()));
  }

  /**
   * A database that never touches the filesystem, with the default
   * configuration.
   *
   * <p>Every call makes one of its own. Two connections on one of these are
   * two views of one graph; two of these share nothing, and nothing survives
   * the process.
   *
   * @return the database
   */
  public static Database memory() {
    return memory(Config.defaults());
  }

  /**
   * A database that never touches the filesystem.
   *
   * @param config the caches and the workers
   * @return the database
   */
  public static Database memory(Config config) {
    Objects.requireNonNull(config, "config");
    ZuBinding zu = Zu.binding();
    return new Database(
        zu, zu.databaseMemory(config.memoryLimit(), config.threads(), config.readOnly()));
  }

  /**
   * A connection, which keeps the catalog, the statistics, the plan cache and
   * the block caches resident, so queries after the first run without
   * touching the catalog on disk.
   *
   * <p>That is also why it is per connection rather than per database, and
   * why a pool calls this once per worker instead of sharing one.
   *
   * @return a new connection
   */
  public Connection connect() {
    return new Connection(zu, zu.connect(open()));
  }

  /**
   * What this process calls the database.
   *
   * <p>For one in memory this is a name and not a path: it is what an error
   * message needs, and not something to open.
   *
   * @return the name
   */
  public String path() {
    return zu.databasePath(open());
  }

  /**
   * Whether this database is in memory, which is the way to ask rather than
   * to read {@link #path()} and guess.
   *
   * @return true for a database in memory
   */
  public boolean isMemory() {
    return zu.databaseIsMemory(open());
  }

  /**
   * Whether this database has been closed.
   *
   * @return true once {@link #close()} has run
   */
  public boolean isClosed() {
    return handle.get() == 0;
  }

  /** Releases the path and the configuration. Closing twice does nothing the second time. */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.databaseClose(h);
    }
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(
          Diagnostic.misuse(Status.MISUSE_CLOSED, "this database is closed"));
    }
    return h;
  }
}
