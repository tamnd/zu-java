/**
 * A zu result as an Arrow reader.
 *
 * <p>This module is where arrow-java is named and the only place in this
 * client that names it. A program that reads rows or columns depends on {@code
 * dev.zudb} and carries nothing of Arrow; a program that wants Arrow adds this
 * and gets the reader every Arrow consumer on the JVM already takes.
 */
module dev.zudb.arrow {
  // Transitive, all three of them, because they are the types on the
  // three methods this module has: a caller passes an allocator and a
  // result and is handed a reader, so a caller that reads this module
  // reads those as well or cannot call it at all.
  requires transitive dev.zudb;
  requires transitive org.apache.arrow.memory.core;
  requires transitive org.apache.arrow.vector;

  requires org.apache.arrow.c;

  exports dev.zudb.arrow;
}
