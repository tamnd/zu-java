package dev.zudb.corpus;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The corpus itself, run against this client.
 *
 * <p>The cases live in the engine's repository and are versioned with it,
 * so this test says where they are with an environment variable and skips
 * without one. That is what zu-go and zu-python do with ZU_CASES and what
 * makes a checkout of this repository alone still {@code mvn test} green: a
 * client whose test suite cannot run without a second repository beside it
 * is one nobody clones to fix a typo.
 *
 * <p>CI sets the variable, having checked the engine out at the revision the
 * staged library was built from. Anything else compares a client against a
 * corpus that is not the one it was built against, which reports the engine
 * catching up to its own cases as this client failing.
 */
final class CorpusTest {

  /** Where the case files are, or null when nobody said. */
  private static final String CASES = System.getenv("ZU_CASES");

  @TempDir Path work;

  /** Skips a test that has no corpus to run. */
  private static void needsCases() {
    assumeTrue(CASES != null && !CASES.isBlank(), "ZU_CASES does not point at the case files");
  }

  @Test
  void theCorpusReads() {
    needsCases();
    List<Suite> suites = Suite.readDir(Path.of(CASES));
    int total = 0;
    for (Suite suite : suites) {
      total += suite.cases().size();
    }
    assertNotEquals(0, total, suites.size() + " suites and no cases in any of them");
    System.out.println(suites.size() + " suites, " + total + " cases");
  }

  /**
   * The run, which is the whole point of the module.
   *
   * <p>A case the engine has not caught up to is unsupported and is not a
   * failure, because the corpus is the contract and the engine catches up
   * to it. A case that fails is this client answering a question wrongly,
   * and there is no allowance for one.
   */
  @Test
  void everyCaseInTheCorpusPassesOrIsAheadOfTheEngine() {
    needsCases();
    List<Suite> suites = Suite.readDir(Path.of(CASES));
    Runner.Report report = Runner.run(suites, work);
    StringBuilder failed = new StringBuilder();
    for (Runner.Ran ran : report.ran()) {
      if (ran.outcome() == Runner.Outcome.FAILED) {
        failed.append(ran).append('\n');
      }
    }
    System.out.println(report.summary());
    // The ones ahead of the engine are listed rather than counted, so that
    // a release branch has something to read and so that a case quietly
    // becoming unsupported is visible in the log. The corpus is run once
    // and read twice, because running it is minutes.
    for (Runner.Ran ran : report.ran()) {
      if (ran.outcome() == Runner.Outcome.UNSUPPORTED) {
        System.out.println("  " + ran);
      }
    }
    assertTrue(failed.isEmpty(), failed.toString());
    // A run where nothing passed is a run that did not happen, which is
    // what a corpus read from the wrong directory or a library that
    // answers nothing looks like from here.
    assertNotEquals(0, report.count(Runner.Outcome.PASSED),
        "no case passed, and a run where nothing passes is a run that did not happen");
  }
}
