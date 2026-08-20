package dev.zudb;

/**
 * One row of a {@link Result}, by column number or by name.
 *
 * <p>A row is a position rather than a copy. It holds no values of its own
 * and reads them out of the result as it is asked, so it is good exactly as
 * long as the result is and no longer.
 *
 * <p>The typed accessors are the short way to read a cell whose type you
 * know, and they refuse a cell that is null rather than answering zero, since
 * a {@code long} has no way to say that there was nothing there.
 * {@link #isNull(int)} is how to ask, and {@link #get(int)} is how to read a
 * cell whose type you do not know or whose null you want as a value.
 */
public final class Row {

  private final Result result;
  private final long index;

  Row(Result result, long index) {
    this.result = result;
    this.index = index;
  }

  /**
   * Which row of the result this is.
   *
   * @return the index, counting from zero
   */
  public long index() {
    return index;
  }

  /**
   * The result this row is part of.
   *
   * @return the result
   */
  public Result result() {
    return result;
  }

  /**
   * Whether a cell is null.
   *
   * @param column the column, counting from zero
   * @return true if there is no value there
   */
  public boolean isNull(int column) {
    return result.cellType(index, column) == Type.NULL;
  }

  /**
   * Whether a cell is null.
   *
   * @param column what the statement called it
   * @return true if there is no value there
   */
  public boolean isNull(String column) {
    return isNull(result.columnIndex(column));
  }

  /**
   * What a cell holds, without reading it.
   *
   * @param column the column, counting from zero
   * @return the type
   */
  public Type type(int column) {
    return result.cellType(index, column);
  }

  /**
   * What a cell holds, without reading it.
   *
   * @param column what the statement called it
   * @return the type
   */
  public Type type(String column) {
    return type(result.columnIndex(column));
  }

  /**
   * One cell, as the type it actually is.
   *
   * <p>Strings in the tree are copied out on the way, so what comes back is
   * Java objects and outlives nothing the result does not.
   *
   * @param column the column, counting from zero
   * @return the value, {@link Value.Null} for a cell with nothing in it
   */
  public Value get(int column) {
    result.checkColumn(column);
    return result.read(result.zu().resultCell(result.open(), index, column));
  }

  /**
   * One cell, as the type it actually is.
   *
   * @param column what the statement called it
   * @return the value
   */
  public Value get(String column) {
    return get(result.columnIndex(column));
  }

  /**
   * A cell as an integer.
   *
   * @param column the column, counting from zero
   * @return the integer
   * @throws ZuProgrammingException if the cell is null or holds something else
   */
  public long getLong(int column) {
    Value v = get(column);
    if (v instanceof Value.Int i) {
      return i.value();
    }
    throw wrong(column, v, "an integer");
  }

  /**
   * A cell as an integer.
   *
   * @param column what the statement called it
   * @return the integer
   */
  public long getLong(String column) {
    return getLong(result.columnIndex(column));
  }

  /**
   * A cell as a float, widening an integer on the way, which is the one
   * conversion this client makes without being asked and is the one every
   * arithmetic in Java makes too.
   *
   * @param column the column, counting from zero
   * @return the double
   * @throws ZuProgrammingException if the cell is null or holds something else
   */
  public double getDouble(int column) {
    Value v = get(column);
    if (v instanceof Value.Float f) {
      return f.value();
    }
    if (v instanceof Value.Int i) {
      return i.value();
    }
    throw wrong(column, v, "a float");
  }

  /**
   * A cell as a float.
   *
   * @param column what the statement called it
   * @return the double
   */
  public double getDouble(String column) {
    return getDouble(result.columnIndex(column));
  }

  /**
   * A cell as a boolean.
   *
   * @param column the column, counting from zero
   * @return the boolean
   * @throws ZuProgrammingException if the cell is null or holds something else
   */
  public boolean getBoolean(int column) {
    Value v = get(column);
    if (v instanceof Value.Bool b) {
      return b.value();
    }
    throw wrong(column, v, "a boolean");
  }

  /**
   * A cell as a boolean.
   *
   * @param column what the statement called it
   * @return the boolean
   */
  public boolean getBoolean(String column) {
    return getBoolean(result.columnIndex(column));
  }

  /**
   * A cell as a string.
   *
   * <p>Null rather than a throw for a cell with nothing in it, because a
   * {@code String} can say that and a {@code long} cannot, and because a
   * column of names with a gap in it is an ordinary thing to read.
   *
   * @param column the column, counting from zero
   * @return the string, or null if the cell is null
   * @throws ZuProgrammingException if the cell holds something that is not a
   *     string
   */
  public String getString(int column) {
    result.checkColumn(column);
    Type type = result.cellType(index, column);
    if (type == Type.NULL) {
      return null;
    }
    if (type != Type.STR) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "column " + result.columnName(column) + " of row " + index + " holds " + type
                  + " and was read as a string"));
    }
    return result.zu().resultCellString(result.open(), index, column);
  }

  /**
   * A cell as a string.
   *
   * @param column what the statement called it
   * @return the string, or null if the cell is null
   */
  public String getString(String column) {
    return getString(result.columnIndex(column));
  }

  /**
   * A cell as a date, a time, a datetime or a duration.
   *
   * @param column the column, counting from zero
   * @return the temporal, which knows which of the seven it is
   * @throws ZuProgrammingException if the cell is null or holds something else
   */
  public Value.Temporal getTemporal(int column) {
    Value v = get(column);
    if (v instanceof Value.Temporal t) {
      return t;
    }
    throw wrong(column, v, "a temporal");
  }

  /**
   * A cell as a temporal.
   *
   * @param column what the statement called it
   * @return the temporal
   */
  public Value.Temporal getTemporal(String column) {
    return getTemporal(result.columnIndex(column));
  }

  /**
   * A cell as a node.
   *
   * @param column the column, counting from zero
   * @return the node, which is a table and a row of it
   * @throws ZuProgrammingException if the cell is null or holds something else
   */
  public Value.Node getNode(int column) {
    Value v = get(column);
    if (v instanceof Value.Node n) {
      return n;
    }
    throw wrong(column, v, "a node");
  }

  /**
   * A cell as a node.
   *
   * @param column what the statement called it
   * @return the node
   */
  public Value.Node getNode(String column) {
    return getNode(result.columnIndex(column));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("row ").append(index).append(" {");
    for (int c = 0; c < result.columns(); c++) {
      if (c > 0) {
        sb.append(", ");
      }
      sb.append(result.columnName(c)).append('=').append(get(c));
    }
    return sb.append('}').toString();
  }

  private ZuProgrammingException wrong(int column, Value value, String wanted) {
    String held =
        value instanceof Value.Null
            ? "nothing"
            : value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    return new ZuProgrammingException(
        Diagnostic.misuse(
            Status.MISUSE,
            "column "
                + result.columnName(column)
                + " of row "
                + index
                + " holds "
                + held
                + " and was read as "
                + wanted));
  }
}
