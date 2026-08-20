/**
 * What a binding to the native library has to implement.
 *
 * <p>Nothing in here is for the program that is querying a database. It is for
 * the two artifacts that make the calls, {@code zudb-ffm} over Panama and
 * {@code zudb-jni} over JNI, and for anyone who wants a third.
 *
 * <p>The shape is one interface, {@link dev.zudb.spi.ZuBinding}, over {@code
 * long} handles, so that a provider owns the calls and nothing else. Turning a
 * status into an exception, reading a value tree, caching column names: all of
 * that happens once in {@code dev.zudb} and cannot drift between providers.
 */
package dev.zudb.spi;
