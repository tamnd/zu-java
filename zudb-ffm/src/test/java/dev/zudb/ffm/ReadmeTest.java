package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.tck.Libzu;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The programs on the front page, run as printed.
 *
 * <p>A quickstart is the most read and the least executed code a client has.
 * It is what somebody copies before they have opened the reference, and it
 * goes wrong a rename at a time: a method loses an argument, a return type
 * narrows, an example keeps compiling in a reader's head and nowhere else.
 * The only fix that holds is to run the page.
 *
 * <p>Run means run. The source is taken off the README character for
 * character, written into an empty directory somewhere else on the machine,
 * and compiled and executed by a JVM of its own with the client on its class
 * path and nothing else. Nothing about this repository's build reaches it: no
 * test framework, no fixture, no working directory with a database already in
 * it. What it prints is compared against what the page says it prints, which
 * is the other half of the claim and the half that rots quietest.
 *
 * <p>A block that declares a public class is a whole program and a block that
 * does not is a fragment, which is a rule the page can be read against rather
 * than a list kept here. The fenced block after a whole program is its
 * output.
 */
class ReadmeTest {

  /** The page. Surefire runs a module in its own directory, so this is the root of the repository. */
  private static final Path README = Paths.get("..", "README.md");

  /** What makes a block a program rather than a fragment, and what names it. */
  private static final Pattern DECLARES = Pattern.compile("^public class (\\w+) \\{$");

  @TempDir static Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  /** A program on the page, the class it declares, and the lines it says it prints. */
  private record Program(String name, String source, String output) {}

  @Test
  void thePageHasAProgramOnIt() throws IOException {
    // A rule that quietly matches nothing leaves a green suite that runs no
    // programs, which is worse than the page being wrong, because a suite
    // that tests nothing is one nobody looks at again.
    assertFalse(programs().isEmpty(), "no whole program on " + README.toAbsolutePath().normalize());
  }

  @TestFactory
  List<DynamicTest> everyProgramOnThePageRunsAndPrintsWhatItSays() throws IOException {
    List<DynamicTest> cases = new ArrayList<>();
    for (Program program : programs()) {
      cases.add(DynamicTest.dynamicTest(program.name(), () -> assertEquals(program.output(), run(program))));
    }
    return cases;
  }

  /**
   * Compiles and runs one program in a directory of its own.
   *
   * <p>Single file source mode, because that is the shortest way to run
   * exactly the text on the page and because a reader with the jars and a JDK
   * can do the same thing. The class path is the client as this build made
   * it, so a signature that moved in this commit is a compile failure here
   * rather than a surprise after a release.
   */
  private static String run(Program program) throws Exception {
    Path where = Files.createDirectories(dir.resolve(program.name()));
    Path source = where.resolve(program.name() + ".java");
    Files.writeString(source, program.source(), StandardCharsets.UTF_8);
    Path complaints = where.resolve("stderr.txt");

    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    // From JDK 24 the grant belongs to whoever starts the JVM, which here is
    // this process starting that one. A manifest carries it only for the jar
    // that java -jar names, and a class path run is not that.
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-Dzu.library=" + System.getProperty("zu.library"));
    command.add("-classpath");
    command.add(classpath());
    command.add(source.toString());

    Process java =
        new ProcessBuilder(command)
            .directory(where.toFile())
            .redirectError(complaints.toFile())
            .start();
    String printed = new String(java.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(java.waitFor(5, TimeUnit.MINUTES), program.name() + " did not finish");
    assertEquals(
        0,
        java.exitValue(),
        program.name() + " exited " + java.exitValue() + ":\n" + Files.readString(complaints));
    return printed;
  }

  /**
   * What the child JVM is given: this provider, and the API it implements.
   *
   * <p>The dependency list is written out by the build rather than worked out
   * here, because a test that guesses at a sibling module's target directory
   * is a test that passes for the wrong reason the day the graph changes.
   */
  private static String classpath() throws IOException {
    Path written = Paths.get("target", "readme-classpath.txt");
    assertTrue(
        Files.isRegularFile(written),
        written.toAbsolutePath().normalize() + " is missing: the build writes it before the tests run");
    String provider = Paths.get("target", "classes").toAbsolutePath().toString();
    String rest = Files.readString(written, StandardCharsets.UTF_8).trim();
    return rest.isEmpty() ? provider : provider + File.pathSeparator + rest;
  }

  /** Every whole program on the page, with the output block that follows it. */
  private static List<Program> programs() throws IOException {
    List<String> lines = Files.readAllLines(README, StandardCharsets.UTF_8);
    List<Program> found = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).equals("```java")) {
        continue;
      }
      int end = close(lines, i + 1, i);
      String name = declared(lines.subList(i + 1, end));
      if (name == null) {
        i = end;
        continue;
      }
      int opens = fence(lines, end + 1);
      assertTrue(opens >= 0, name + " has no output block after it");
      assertEquals("```", lines.get(opens), name + " is followed by a block that is not its output");
      int closes = close(lines, opens + 1, opens);
      found.add(
          new Program(
              name,
              join(lines.subList(i + 1, end)),
              join(lines.subList(opens + 1, closes))));
      i = closes;
    }
    return found;
  }

  /** Where the block opened at {@code opened} ends. */
  private static int close(List<String> lines, int from, int opened) {
    for (int i = from; i < lines.size(); i++) {
      if (lines.get(i).equals("```")) {
        return i;
      }
    }
    throw new AssertionError("the block at line " + (opened + 1) + " is never closed");
  }

  /** The next fence at or after {@code from}, or -1 if the page ends first. */
  private static int fence(List<String> lines, int from) {
    for (int i = from; i < lines.size(); i++) {
      if (lines.get(i).startsWith("```")) {
        return i;
      }
    }
    return -1;
  }

  /** The class a block declares, or null when it declares none and is a fragment. */
  private static String declared(List<String> block) {
    for (String line : block) {
      Matcher m = DECLARES.matcher(line);
      if (m.matches()) {
        return m.group(1);
      }
    }
    return null;
  }

  private static String join(List<String> lines) {
    StringBuilder text = new StringBuilder();
    for (String line : lines) {
      text.append(line).append('\n');
    }
    return text.toString();
  }
}
