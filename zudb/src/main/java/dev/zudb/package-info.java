/**
 * An embedded property graph database, in this process.
 *
 * <p>Open a database, take a connection, run a statement, read the rows:
 *
 * <pre>{@code
 * try (Database db = Database.open("graph.zu");
 *      Connection conn = db.connect();
 *      Result result = conn.query("MATCH (p:Person) RETURN p.name AS name, p.age AS age")) {
 *     for (Row row : result) {
 *         System.out.println(row.getString("name") + " is " + row.getLong("age"));
 *     }
 * }
 * }</pre>
 *
 * <p>Everything that holds something native is {@link java.lang.AutoCloseable}
 * and is closed in the order it was opened, which a single try-with-resources
 * does for you. Nothing here is a finalizer and nothing waits for a collector.
 *
 * <p>Results are columnar underneath, and a program that is summing rather
 * than printing reads a column at a time through {@link dev.zudb.Result#longs}
 * and its neighbours, which hand back a {@link java.nio.Buffer} over the
 * engine's own memory with no copy on the way.
 *
 * <p>Failures are {@link dev.zudb.ZuException} and its subclasses, unchecked,
 * each carrying the {@link dev.zudb.Diagnostic} the engine produced, which is
 * a GQLSTATUS code, a condition, a position in the statement and the line it
 * came from.
 *
 * <p>Threads: a {@link dev.zudb.Database} is safe to share, a {@link
 * dev.zudb.Connection} is not. Give each thread its own through {@link
 * dev.zudb.Database#connect()} or {@link dev.zudb.Connection#duplicate()}, and
 * they will share the one database underneath.
 */
package dev.zudb;
