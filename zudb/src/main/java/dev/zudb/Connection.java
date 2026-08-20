package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The state that cannot be shared: a file handle, the caches, and the plans
 * compiled against a catalog.
 *
 * <p>A connection may move between threads but must not be used from two at
 * once. A call that finds one already in use raises
 * {@link ZuConcurrentException} rather than corrupting a cache, so a program
 * that shares one fails under load and passes every test. A program that
 * queries from four threads opens one {@link Database} and calls
 * {@link Database#connect()} four times, or {@link #duplicate()} where it no
 * longer has the database.
 *
 * <p>{@link #interrupt()} and {@link #rowsRead()} are the exception and the
 * point of it. Both are meant to be called from another thread while a
 * statement is running, and neither raises {@link ZuConcurrentException}: a
 * cancellation that had to wait for the connection to be free could only
 * arrive after the statement it was meant to stop.
 */
public final class Connection implements AutoCloseable {

  private final ZuBinding zu;
  private final AtomicLong handle;

  Connection(ZuBinding zu, long handle) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
  }

  /**
   * Runs one statement and hands back everything it answered.
   *
   * <p>The result owns its rows outright, so it stays readable after this
   * connection has gone back to a pool. What it does not outlive is its own
   * {@link Result#close()}.
   *
   * @param statement the text
   * @return the result, which the caller closes
   */
  public Result query(String statement) {
    return new Result(zu, zu.query(open(), statement));
  }

  /**
   * Runs one statement and throws away what it answered, for the statement
   * there is nothing to read from.
   *
   * @param statement the text
   */
  public void execute(String statement) {
    query(statement).close();
  }

  /**
   * Prepares a statement, which is parsed and planned once and run as often
   * as you like.
   *
   * <p>Bindings live on the statement and survive an execute, so a loop
   * rebinds only what changed.
   *
   * @param statement the text, with its parameters named
   * @return the statement, which the caller closes
   */
  public Statement prepare(String statement) {
    return new Statement(zu, zu.prepare(open(), statement));
  }

  /**
   * A second connection on the database this one is already on, made without
   * a path.
   *
   * <p>This is what a pool calls once it has handed the database back, and it
   * is the only way to a second connection on a database in memory, which has
   * no path to reopen. The switches and the read-only setting come across;
   * the plan cache, the block caches, the interrupt and the transaction do
   * not, because those are what make it a connection of its own.
   *
   * @return a new connection
   */
  public Connection duplicate() {
    return new Connection(zu, zu.connDuplicate(open()));
  }

  /**
   * Stops whatever is running on this connection, from another thread.
   *
   * <p>The statement stops at the next boundary the executor checks, which is
   * a chunk of rows rather than the end of the query, and raises
   * {@link ZuInterruptedException}. Nothing failed: the connection keeps its
   * plans and its warm caches and runs the next statement normally, which is
   * the difference between this and closing it.
   *
   * <p>An ask raised while nothing is running is dropped when the next
   * statement starts, so a Ctrl-C at a prompt cannot end whatever the user
   * types next.
   *
   * <p>What is safe from another thread is a statement running. Closing the
   * connection underneath this call is not, and no amount of locking here
   * could make it so: the program has to know that the connection is still
   * there.
   */
  public void interrupt() {
    zu.connInterrupt(open());
  }

  /**
   * How many rows the running statement has read out of storage, counted from
   * zero at each statement and left at its final value once one ends.
   *
   * <p>Rows read rather than rows answered, because the statement a user is
   * waiting on is exactly the one reading a hundred million rows to answer
   * one. Safe from another thread, which is what a progress bar needs.
   *
   * @return the count
   */
  public long rowsRead() {
    return zu.connRowsRead(open());
  }

  /**
   * Starts a transaction.
   *
   * <p>Every statement outside one is already a transaction of its own, so
   * this does not turn transactions on. What it does is make several
   * statements one: what they wrote is kept by {@link #commit()} or unmade by
   * {@link #rollback()}, and nothing between the two is visible to another
   * connection until the commit publishes it.
   */
  public void begin() {
    zu.begin(open(), false);
  }

  /**
   * Starts a transaction that refuses writes, which is enforced rather than
   * advisory: a write inside one is refused at the statement that wrote, not
   * at the commit.
   */
  public void beginReadOnly() {
    zu.begin(open(), true);
  }

  /**
   * Keeps what the transaction wrote. The log frame is on the disk before
   * this returns.
   */
  public void commit() {
    zu.commit(open());
  }

  /** Unmakes what the transaction wrote. */
  public void rollback() {
    zu.rollback(open());
  }

  /**
   * Whether a transaction is running.
   *
   * <p>This is the one thing about a transaction that no statement answers,
   * and every block that ends one needs it: the cleanup path has to know
   * whether the body already did.
   *
   * @return true inside a transaction
   */
  public boolean inTransaction() {
    return zu.connInTransaction(open());
  }

  /**
   * Runs a block inside a transaction, committing if it returns and rolling
   * back if it throws.
   *
   * <p>A body that commits or rolls back for itself is left alone rather than
   * committed twice, which is why {@link #inTransaction()} exists.
   *
   * @param body what to run
   */
  public void transaction(Runnable body) {
    transaction(
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * The same, for a block that has an answer.
   *
   * @param <T> what the block answers
   * @param body what to run
   * @return what the body returned
   */
  public <T> T transaction(Supplier<T> body) {
    begin();
    T value;
    try {
      value = body.get();
    } catch (RuntimeException | Error e) {
      if (inTransaction()) {
        try {
          rollback();
        } catch (RuntimeException suppressed) {
          e.addSuppressed(suppressed);
        }
      }
      throw e;
    }
    if (inTransaction()) {
      commit();
    }
    return value;
  }

  /**
   * Whether this connection has been closed.
   *
   * @return true once {@link #close()} has run
   */
  public boolean isClosed() {
    return handle.get() == 0;
  }

  /**
   * Closes the connection, rolling back a transaction still running, which is
   * what a program that failed halfway and dropped everything wants and the
   * only answer that does not depend on a finalizer.
   *
   * <p>Closing is itself a use of the connection and obeys the same rule as
   * every other one. Closing twice does nothing the second time.
   */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.connClose(h);
    }
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(
          Diagnostic.misuse(Status.MISUSE_CLOSED, "this connection is closed"));
    }
    return h;
  }
}
