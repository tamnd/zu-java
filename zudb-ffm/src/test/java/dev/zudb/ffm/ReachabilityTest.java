package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zudb.Connection;
import dev.zudb.Frame;
import dev.zudb.Result;
import dev.zudb.tck.Libzu;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The file an ahead-of-time image builder reads, checked against what this
 * binding actually binds.
 *
 * <p>A native image has no linker at run time. Every downcall stub and every
 * upcall stub is machine code generated while the image is built, and the
 * builder can only generate the ones it was told about, because a signature
 * here is assembled out of ordinary objects at run time and there is nothing
 * in the bytecode to read it off. Told wrong, the image builds clean and dies
 * on a call a JVM run makes every time.
 *
 * <p>So the file is not maintained, it is derived. {@link Shapes} remembers
 * every shape as it is bound, this writes out what that comes to, and the
 * build fails if the file in the repository says something else. Adding a
 * function to the C ABI therefore either changes nothing here, because its
 * shape is one of the forty already listed, or fails this test with the file
 * that would have been right sitting in {@code target/}.
 */
class ReachabilityTest {

  private static final Path CHECKED_IN =
      Paths.get("src/main/resources/META-INF/native-image/dev.zudb/zudb-ffm")
          .resolve("reachability-metadata.json");

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void theImageBuilderIsToldEveryShapeThisBindingBinds() throws Exception {
    // Loading the library binds every downcall. The two upcalls are bound
    // when something wants one, so something has to want one: a registered
    // frame owns the release callback and a watcher owns the progress
    // callback, and between them that is every direction this binding goes.
    try (Connection conn = Connection.memory()) {
      conn.onProgress(Duration.ofMillis(1), (rows, millis) -> true);
      try (Frame frame = Frame.of("Reachable", 1)) {
        frame.column(
            "id",
            java.nio.ByteBuffer.allocateDirect(8)
                .order(java.nio.ByteOrder.nativeOrder())
                .asLongBuffer()
                .put(0, 1));
        conn.register(frame);
      }
      try (Result r = conn.query("RETURN 1 AS one")) {
        assertEquals(1L, r.row(0).getLong(0));
      }
    }

    String want = json();
    Path fallback = Paths.get("target", "reachability-metadata.json");
    String have = Files.exists(CHECKED_IN) ? Files.readString(CHECKED_IN) : "";
    if (!want.equals(have)) {
      Files.createDirectories(fallback.getParent());
      Files.writeString(fallback, want, StandardCharsets.UTF_8);
    }
    assertEquals(
        want,
        have,
        CHECKED_IN
            + " is not what this binding binds. What it should say is in "
            + fallback.toAbsolutePath()
            + ", so copy that over it");
  }

  /**
   * What was bound, in the shape the image builder reads.
   *
   * <p>Sorted rather than in binding order, so that the file is the same file
   * however the constructor is rearranged and a diff on it is about what
   * changed rather than about what moved.
   */
  private static String json() {
    var downcalls = new TreeSet<String>();
    var upcalls = new TreeSet<String>();
    for (Shapes.Shape shape : Shapes.seen()) {
      (shape.up() ? upcalls : downcalls).add(entry(shape));
    }
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"foreign\": {\n");
    sb.append("    \"downcalls\": [\n").append(String.join(",\n", downcalls)).append("\n    ],\n");
    sb.append("    \"upcalls\": [\n").append(String.join(",\n", upcalls)).append("\n    ]\n");
    sb.append("  }\n}\n");
    return sb.toString();
  }

  private static String entry(Shapes.Shape shape) {
    FunctionDescriptor d = shape.descriptor();
    List<String> parameters = new ArrayList<>();
    for (MemoryLayout layout : d.argumentLayouts()) {
      parameters.add("\"" + type(layout) + "\"");
    }
    StringBuilder sb = new StringBuilder("      {");
    sb.append("\"returnType\": \"")
        .append(d.returnLayout().map(ReachabilityTest::type).orElse("void"))
        .append("\", ");
    sb.append("\"parameterTypes\": [").append(String.join(", ", parameters)).append("]");
    if (shape.critical()) {
      // Bound with Linker.Option.critical(false), and a stub for a critical
      // call is not the same machine code as a stub for an ordinary one, so
      // the two are two entries even where the signature is the same.
      sb.append(", \"options\": {\"critical\": {\"allowHeapAccess\": false}}");
    }
    return sb.append("}").toString();
  }

  /**
   * A layout as the image builder spells it, which is C's spelling rather
   * than Java's: the file names canonical layouts, the ones
   * {@link java.lang.foreign.Linker#canonicalLayouts()} answers to.
   *
   * <p>{@code long long} rather than {@code long} for a 64-bit integer, and
   * that matters: C's {@code long} is four bytes on Windows and eight
   * everywhere else, and this file is written once and read on all of them.
   * {@code size_t} is deliberately not used for the same reason, since a
   * descriptor built from it is the same descriptor as one built from a
   * {@code long long} on every platform this ships to and naming the concrete
   * width keeps the file from meaning two things.
   */
  private static String type(MemoryLayout layout) {
    if (layout instanceof AddressLayout) {
      return "void*";
    }
    if (layout instanceof ValueLayout value) {
      return switch (value.carrier().getSimpleName()) {
        case "int" -> "int";
        case "long" -> "long long";
        case "double" -> "double";
        case "float" -> "float";
        case "short" -> "short";
        case "byte" -> "char";
        case "boolean" -> "bool";
        default -> throw new IllegalStateException("no spelling for " + layout);
      };
    }
    throw new IllegalStateException("no spelling for " + layout);
  }
}
