package dev.zudb.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** The files a benchmark that writes to disk leaves behind. */
final class Temp {

  private Temp() {}

  /** Removes a directory and everything under it, sidecars included. */
  static void deleteTree(Path root) throws IOException {
    if (root == null) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(Temp::delete);
    }
  }

  private static void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
