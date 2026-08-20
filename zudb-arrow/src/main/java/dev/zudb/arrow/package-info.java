/**
 * A zu result as Arrow, over the C Data Interface.
 *
 * <p>One class, {@link dev.zudb.arrow.Arrow}, and three static methods on it.
 * Everything else a program needs on this path is arrow-java's own, because
 * what comes back is an {@link org.apache.arrow.vector.ipc.ArrowReader} and
 * every Arrow consumer on the JVM already takes one.
 *
 * <p>This lives in an artifact of its own so that the client keeps its
 * dependencies at none. A program reading rows or columns has no reason to
 * carry arrow-java, and a program that wants Arrow adds one line to a build
 * file.
 */
package dev.zudb.arrow;
