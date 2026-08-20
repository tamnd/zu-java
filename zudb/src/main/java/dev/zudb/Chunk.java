package dev.zudb;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;

/**
 * A run of rows out of a {@link Result}, and the columns of it read where
 * they lie.
 *
 * <p>The trade against reading a whole column is a lifetime. A chunk's buffer
 * is valid until the next call for the same column and the same accessor,
 * which replaces its contents, or until the result closes. A program that
 * needs one chunk to outlive the next copies it, which is the copy it was
 * making anyway on the way into an array of its own.
 *
 * <p>Ask a chunk its size rather than multiplying. Chunks are the same size
 * today except the last, and will stop being once a chunk is what the
 * executor produced rather than a slice of what it materialised.
 *
 * <pre>{@code
 * long total = 0;
 * for (Chunk c : result.chunks().toList()) {
 *     LongBuffer ids = c.longs(0);
 *     for (int i = 0; i < c.rows(); i++) {
 *         total += ids.get(i);
 *     }
 * }
 * }</pre>
 */
public final class Chunk {

  private final Result result;
  private final long index;
  private final long offset;
  private final long rows;

  Chunk(Result result, long index, long offset, long rows) {
    this.result = result;
    this.index = index;
    this.offset = offset;
    this.rows = rows;
  }

  /**
   * Which chunk this is.
   *
   * @return the index, counting from zero
   */
  public long index() {
    return index;
  }

  /**
   * Which row of the result this chunk starts at, which is how a value read
   * here is matched to a cell accessor that takes a row number.
   *
   * @return the row, counting from zero
   */
  public long offset() {
    return offset;
  }

  /**
   * How many rows this chunk holds.
   *
   * @return the count
   */
  public long rows() {
    return rows;
  }

  /**
   * One row of the result, as a {@link Row}.
   *
   * @param row the row within this chunk, counting from zero
   * @return the row
   */
  public Row row(long row) {
    return result.row(offset + row);
  }

  /**
   * This chunk of a column of integers.
   *
   * @param column the column, which must hold integers or booleans
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  public LongBuffer longs(int column) {
    result.checkColumn(column);
    return result.zu().chunkLongs(result.open(), index, column, rows);
  }

  /**
   * This chunk of a column of floats.
   *
   * @param column the column, which must hold floats or integers
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  public DoubleBuffer doubles(int column) {
    result.checkColumn(column);
    return result.zu().chunkDoubles(result.open(), index, column, rows);
  }

  /**
   * This chunk of a column of node row offsets.
   *
   * @param column the column, which must hold nodes
   * @return a read-only view, good until the next call for the same column
   *     and the same accessor
   */
  public LongBuffer nodeOffsets(int column) {
    result.checkColumn(column);
    return result.zu().chunkNodeOffsets(result.open(), index, column, rows);
  }

  /**
   * Which values of this chunk of a column are not null, one byte a row.
   *
   * <p>Columns are independent of each other, so reading a chunk's values and
   * its validity together costs no reconversion.
   *
   * @param column the column
   * @return a read-only view where a nonzero byte is a value
   */
  public ByteBuffer valid(int column) {
    result.checkColumn(column);
    return result.zu().chunkValid(result.open(), index, column, rows);
  }
}
