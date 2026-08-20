package dev.zudb.arrow;

import dev.zudb.Connection;
import dev.zudb.Result;
import java.util.Objects;
import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;

/**
 * A result as Arrow, without a copy on the way.
 *
 * <pre>{@code
 * try (BufferAllocator allocator = new RootAllocator();
 *     ArrowReader reader = Arrow.query(allocator, conn, "MATCH (p:Person) RETURN p.id AS id")) {
 *   while (reader.loadNextBatch()) {
 *     BigIntVector ids = (BigIntVector) reader.getVectorSchemaRoot().getVector(0);
 *     for (int i = 0; i < ids.getValueCount(); i++) {
 *       sum += ids.get(i);
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Nothing on this path is proportional to the answer. The arrays that cross
 * are the buffers the engine's executor filled, at the addresses it filled
 * them at, and what an export costs is the schema, the stream and the pointers
 * in it. A million rows and ten thousand cost about the same.
 *
 * <p>That is also why an export spends the result. Once the buffers have left,
 * there is nothing on this side to read a second time, so the {@code Result}
 * handed to any of these is closed by the call and every buffer a columnar
 * reader borrowed from it before now belongs to the Arrow consumer. Closing it
 * again afterwards is the no-op it always was, so a try-with-resources around
 * it is still the right shape.
 *
 * <p>The reader owns what it was given and releases the stream when it closes,
 * which releases the result the stream was made from. Close the reader.
 *
 * <p>A result the engine had to build across its rows, which is anything with
 * an {@code ORDER BY}, has no buffers to move and is read into buffers of its
 * own on the way out. That is the fallback working rather than the fast path
 * failing, and it is still one pass and still correct.
 */
public final class Arrow {

  private Arrow() {}

  /**
   * Runs a statement and hands back its answer as Arrow.
   *
   * @param allocator what the Arrow side allocates from
   * @param conn the connection
   * @param statement the text
   * @return the reader, which the caller closes
   */
  public static ArrowReader query(BufferAllocator allocator, Connection conn, String statement) {
    Objects.requireNonNull(conn, "conn");
    Result result = conn.query(statement);
    try {
      return reader(allocator, result);
    } catch (RuntimeException | Error e) {
      result.close();
      throw e;
    }
  }

  /**
   * A result already in hand, as Arrow, in batches of {@link
   * Result#DEFAULT_BATCH} rows.
   *
   * @param allocator what the Arrow side allocates from
   * @param result the result, which this call spends
   * @return the reader, which the caller closes
   */
  public static ArrowReader reader(BufferAllocator allocator, Result result) {
    return reader(allocator, result, 0);
  }

  /**
   * The same, with the batch size named.
   *
   * @param allocator what the Arrow side allocates from
   * @param result the result, which this call spends
   * @param rowsPerBatch how many rows a consumer sees at a time, or zero for
   *     {@link Result#DEFAULT_BATCH}. The batches are slices of arrays that
   *     are already in memory, so this is about what a consumer likes to work
   *     in and not about what gets allocated
   * @return the reader, which the caller closes
   */
  public static ArrowReader reader(BufferAllocator allocator, Result result, long rowsPerBatch) {
    Objects.requireNonNull(allocator, "allocator");
    Objects.requireNonNull(result, "result");
    ArrowArrayStream stream = ArrowArrayStream.allocateNew(allocator);
    try {
      result.exportArrow(stream.memoryAddress(), rowsPerBatch);
      return Data.importArrayStream(allocator, stream);
    } catch (RuntimeException | Error e) {
      // A refusal leaves the struct as it was allocated, which is
      // released, so this frees the memory it sits in and calls nothing.
      // An import that failed leaves a live stream, and this is what
      // releases it.
      stream.close();
      throw e;
    }
  }
}
