/**
 * The cases every provider owes, and the fixture that finds an engine to run
 * them against.
 *
 * <p>These are main sources rather than test sources because two other
 * modules run them, and a test source set is not a thing another module can
 * depend on without a test jar and the trouble that comes with one.
 *
 * <p>The module is open rather than exporting to a list, because the
 * reflection that reaches a test method comes from the platform, from the
 * engine and from an extension, and a list of three would be a list that goes
 * stale. The package is exported as well so that a provider module can name
 * the fixture in a test of its own.
 */
open module dev.zudb.tck {
  requires dev.zudb;
  requires transitive org.junit.jupiter.api;

  exports dev.zudb.tck;
}
