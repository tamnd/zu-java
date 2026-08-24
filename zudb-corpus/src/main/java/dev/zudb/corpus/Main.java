package dev.zudb.corpus;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the shared cross-client corpus against this client and prints what
 * happened.
 *
 * <pre>{@code
 * java -cp ... dev.zudb.corpus.Main ../zu/conformance/cases
 * }</pre>
 *
 * <p>The report is the reference runner's, line for line, so a disagreement
 * between two clients is a diff and not a reading exercise. It exits zero
 * when nothing failed and one when something did or when the corpus will
 * not read, which is also the reference runner's rule.
 */
public final class Main {

  private Main() {}

  /**
   * The command.
   *
   * @param args the flags and the one directory to read
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * The command, with somewhere to write and a status rather than an exit,
   * which is what a test can call.
   *
   * @param args the flags and the one directory to read
   * @param out where the report goes
   * @param err where a refusal goes
   * @return what the command would exit with
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    boolean strict = false;
    boolean quiet = false;
    String work = "";
    List<String> rest = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "-strict", "--strict" -> strict = true;
        case "-quiet", "--quiet" -> quiet = true;
        case "-work", "--work" -> {
          if (i + 1 == args.length) {
            usage(err);
            return 2;
          }
          work = args[++i];
        }
        default -> {
          if (arg.startsWith("-work=") || arg.startsWith("--work=")) {
            work = arg.substring(arg.indexOf('=') + 1);
          } else if (arg.startsWith("-")) {
            usage(err);
            return 2;
          } else {
            rest.add(arg);
          }
        }
      }
    }
    if (rest.size() != 1) {
      usage(err);
      return 2;
    }

    List<Suite> suites;
    try {
      suites = Suite.readDir(Path.of(rest.get(0)));
    } catch (CorpusException e) {
      // One rather than two, because the reference runner exits one for a
      // corpus it cannot read and a report that is compared line for line
      // is worth less if the two disagree about what the run came to.
      err.println("zu corpus: " + e.getMessage());
      return 1;
    }

    Path directory;
    boolean keep = !work.isEmpty();
    try {
      if (keep) {
        directory = Files.createDirectories(Path.of(work));
      } else {
        // Removed when the run ends, and each case removes its own as it
        // finishes, so what is left in here at the end is the databases of
        // the cases that failed. A run with -work keeps them.
        directory = Files.createTempDirectory("zu-corpus-");
      }
    } catch (IOException e) {
      err.println("zu corpus: " + e);
      return 1;
    }

    Runner.Report report;
    try {
      report = Runner.run(suites, directory);
    } finally {
      if (!keep) {
        removeAll(directory);
      }
    }
    if (!quiet) {
      for (Runner.Ran ran : report.ran()) {
        if (ran.outcome() != Runner.Outcome.PASSED) {
          out.println(ran);
        }
      }
    }
    out.println(report.summary());
    if (report.count(Runner.Outcome.FAILED) > 0) {
      return 1;
    }
    if (strict && report.count(Runner.Outcome.UNSUPPORTED) > 0) {
      return 1;
    }
    return 0;
  }

  private static void usage(PrintStream err) {
    err.println("usage: corpus [flags] <dir>");
    err.println("run the shared corpus cases against this client");
    err.println("  -strict");
    err.println("        an unsupported case fails the run, which is what a release branch wants");
    err.println("  -quiet");
    err.println("        print the summary and nothing else");
    err.println("  -work string");
    err.println("        a directory to make the case databases under, kept rather than removed");
  }

  /**
   * Takes the temporary directory back down, deepest first.
   *
   * <p>A file that will not go is not something to say about a run that has
   * already printed what it came to, so nothing is said about it.
   */
  private static void removeAll(Path directory) {
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          // Left behind in the temporary directory, which is where the
          // operating system will get to it eventually.
        }
      });
    } catch (IOException | UncheckedIOException e) {
      // The same.
    }
  }
}
