package dev.zudb;

/**
 * How a database is opened. Zero means the default in every field, so
 * {@link #defaults()} opens the same database as passing nothing.
 *
 * <p>A record with {@code with} methods rather than a builder, because there
 * are three fields and a builder for three fields is a class to read before
 * you can open a file.
 *
 * @param memoryLimit bytes the caches may hold, 0 for the default. A suffix
 *     such as {@code MB} is deliberately not parsed anywhere in this client:
 *     its two readings differ by 4.9%, and the place to decide which one a
 *     user meant is where the user typed it
 * @param threads query workers, 0 to let the executor pick and 1 for
 *     sequential, which is what a benchmark that wants a number it can
 *     compare asks for
 * @param readOnly whether to open a descriptor this process cannot write
 *     through, which is enforced by the operating system and not by a check
 */
public record Config(long memoryLimit, long threads, boolean readOnly) {

  private static final Config DEFAULTS = new Config(0, 0, false);

  /**
   * Refuses a count that cannot be one.
   *
   * @param memoryLimit bytes, which cannot be negative
   * @param threads workers, which cannot be negative
   * @param readOnly whether writes are refused
   */
  public Config {
    if (memoryLimit < 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(Status.MISUSE, "a memory limit of " + memoryLimit + " bytes"));
    }
    if (threads < 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(Status.MISUSE, "a thread count of " + threads));
    }
  }

  /**
   * Everything left to the engine.
   *
   * @return the default configuration
   */
  public static Config defaults() {
    return DEFAULTS;
  }

  /**
   * The same, with a cache budget.
   *
   * @param bytes what the caches may hold, 0 for the default
   * @return a new configuration
   */
  public Config withMemoryLimit(long bytes) {
    return new Config(bytes, threads, readOnly);
  }

  /**
   * The same, with a worker count.
   *
   * @param count query workers, 0 to let the executor pick, 1 for sequential
   * @return a new configuration
   */
  public Config withThreads(long count) {
    return new Config(memoryLimit, count, readOnly);
  }

  /**
   * The same, refusing writes.
   *
   * @param value whether the descriptor cannot be written through
   * @return a new configuration
   */
  public Config withReadOnly(boolean value) {
    return new Config(memoryLimit, threads, value);
  }
}
