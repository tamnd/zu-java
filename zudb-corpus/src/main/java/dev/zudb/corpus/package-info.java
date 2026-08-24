/**
 * The shared conformance corpus, read and run against this client.
 *
 * <p>The corpus is a directory of YAML files, versioned with the engine
 * and shipped to every client in the family. A case is a statement and
 * what running it must produce, which is deliberately the whole of it:
 * every client in every language can run a statement and look at the rows
 * that come back, so a corpus written in those terms is one every client
 * can run, and a corpus written in terms of a client's own API would be
 * nine corpora.
 *
 * <p>What this prints is what the reference runner in Rust prints, line
 * for line, so that a disagreement between two clients is a diff and not
 * a reading exercise.
 */
package dev.zudb.corpus;
