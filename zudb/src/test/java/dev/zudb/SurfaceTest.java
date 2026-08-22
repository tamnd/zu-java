package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The public surface, written down, so that moving it is something a reviewer
 * sees.
 *
 * <p>A client's API is a promise, and the way that promise gets broken is
 * never a decision anybody announced. It is a parameter that became an
 * {@code Optional}, a class that stopped being public when it moved package, a
 * method that gained an overload and made an existing call ambiguous. Each of
 * those is one line in a diff that looks like an implementation change, and by
 * the time a user finds out the release has gone.
 *
 * <p>So the surface lives in {@code api/surface.txt} at the root of the
 * repository, one line per type and per member, and this test regenerates it
 * and compares. A change to the API is then a change to that file, in the same
 * commit, where it is the first thing in the diff rather than the last. That
 * is the whole mechanism, and it is the same one zu-go uses, which matters
 * because a reviewer moving between the clients should not have to learn a
 * second one.
 *
 * <p>Run with {@code -Dzu.surface.write=true} to write the file instead of
 * checking it. That is the one command to reach for when the change was
 * meant, and running it is how you say so.
 */
class SurfaceTest {

  /** Where the surface is written down, from this module's directory. */
  private static final Path SURFACE = Paths.get("..", "api", "surface.txt");

  /** The packages this module exports, which are the whole of the promise. */
  private static final List<String> EXPORTED = List.of("dev.zudb", "dev.zudb.spi");

  @Test
  void theSurfaceIsWhatTheFileSaysItIs() throws IOException {
    String found = surface();
    if (Boolean.getBoolean("zu.surface.write")) {
      Files.createDirectories(SURFACE.getParent());
      Files.writeString(SURFACE, found, StandardCharsets.UTF_8);
      return;
    }
    assertTrue(
        Files.isRegularFile(SURFACE),
        SURFACE.toAbsolutePath().normalize()
            + " is missing: write it with mvn -pl zudb test -Dzu.surface.write=true");
    String written = Files.readString(SURFACE, StandardCharsets.UTF_8);
    if (written.equals(found)) {
      return;
    }
    // Not assertEquals on the whole thing. Five hundred lines printed twice
    // is a report nobody reads, and the four lines that moved are the report.
    TreeSet<String> was = new TreeSet<>(written.lines().toList());
    TreeSet<String> is = new TreeSet<>(found.lines().toList());
    StringBuilder moved = new StringBuilder();
    for (String line : is) {
      if (!was.contains(line)) {
        moved.append("\n  + ").append(line);
      }
    }
    for (String line : was) {
      if (!is.contains(line)) {
        moved.append("\n  - ").append(line);
      }
    }
    throw new AssertionError(
        "the public surface moved and api/surface.txt did not:"
            + moved
            + "\n\nA name that arrived is a minor release. A name that went or changed shape is a"
            + " major one, or a mistake. If it was meant, write the file down in the same commit"
            + " with mvn -pl zudb test -Dzu.surface.write=true and say in the message which of"
            + " those it is. If it was not, this is the review catching it.");
  }

  @Test
  void theSurfaceIsNotEmpty() throws IOException {
    // A generator that quietly found nothing would make every future change
    // pass, which is the one failure this test cannot report on itself.
    long lines = surface().lines().count();
    assertTrue(lines > 300, "the surface came out at " + lines + " lines, which is not a client");
  }

  /** Every exported type and member, one a line, sorted. */
  private static String surface() throws IOException {
    TreeSet<String> lines = new TreeSet<>();
    for (Class<?> type : types()) {
      lines.add("type " + declaration(type));
      for (Field field : type.getDeclaredFields()) {
        if (published(field.getModifiers()) && !field.isSynthetic()) {
          lines.add(
              "field "
                  + modifiers(field.getModifiers() & Modifier.fieldModifiers())
                  + field.getGenericType().getTypeName()
                  + " "
                  + field.getDeclaringClass().getTypeName()
                  + "."
                  + field.getName());
        }
      }
      for (Constructor<?> ctor : type.getDeclaredConstructors()) {
        if (published(ctor.getModifiers()) && !ctor.isSynthetic()) {
          lines.add(
              "ctor "
                  + modifiers(ctor.getModifiers() & Modifier.constructorModifiers())
                  + generics(ctor.getTypeParameters())
                  + ctor.getDeclaringClass().getTypeName()
                  + arguments(ctor.getGenericParameterTypes())
                  + thrown(ctor.getGenericExceptionTypes()));
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        if (published(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
          lines.add(
              "method "
                  + modifiers(method.getModifiers() & Modifier.methodModifiers())
                  + generics(method.getTypeParameters())
                  + method.getGenericReturnType().getTypeName()
                  + " "
                  + method.getDeclaringClass().getTypeName()
                  + "."
                  + method.getName()
                  + arguments(method.getGenericParameterTypes())
                  + thrown(method.getGenericExceptionTypes()));
        }
      }
    }
    StringBuilder out = new StringBuilder();
    for (String line : lines) {
      out.append(line).append('\n');
    }
    return out.toString();
  }

  /** The modifiers of a type that a caller outside the module can see. */
  private static final int MODIFIERS =
      Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT;

  /**
   * What a type is, spelled out here rather than taken from
   * {@code toGenericString}.
   *
   * <p>That method is not the same on every JDK this client is built for. JDK
   * 21 leaves the sealed marker out of it and JDK 25 puts it in, so a file
   * written on one and checked on the other reports a change nobody made, and
   * a gate that cries wolf on the version matrix is a gate people turn off.
   * Everything here comes off the class file instead.
   *
   * <p>Sealing and the list of permitted subclasses are part of the promise,
   * and so is the interface a type implements, which is how a caller knows a
   * {@link Result} works in a for-each.
   */
  private static String declaration(Class<?> type) {
    StringBuilder out = new StringBuilder();
    String modifiers = Modifier.toString(type.getModifiers() & MODIFIERS);
    if (!modifiers.isEmpty()) {
      out.append(modifiers).append(' ');
    }
    out.append(kind(type)).append(' ').append(type.getName()).append(parameters(type));
    if (type.isSealed()) {
      TreeSet<String> permitted = new TreeSet<>();
      for (Class<?> each : type.getPermittedSubclasses()) {
        permitted.add(each.getTypeName());
      }
      out.append(" sealed permits ").append(String.join(", ", permitted));
    }
    if (type.getSuperclass() != null && type.getSuperclass() != Object.class) {
      out.append(" extends ").append(type.getGenericSuperclass().getTypeName());
    }
    TreeSet<String> faces = new TreeSet<>();
    for (java.lang.reflect.Type each : type.getGenericInterfaces()) {
      faces.add(each.getTypeName());
    }
    if (!faces.isEmpty()) {
      out.append(type.isInterface() ? " extends " : " implements ")
          .append(String.join(", ", faces));
    }
    return out.toString();
  }

  /** Which of the five a type is. */
  private static String kind(Class<?> type) {
    if (type.isAnnotation()) {
      return "@interface";
    }
    if (type.isInterface()) {
      return "interface";
    }
    if (type.isEnum()) {
      return "enum";
    }
    if (type.isRecord()) {
      return "record";
    }
    return "class";
  }

  /** Modifiers with a space after them, or nothing at all. */
  private static String modifiers(int bits) {
    String words = Modifier.toString(bits);
    return words.isEmpty() ? "" : words + " ";
  }

  /** The argument list of a constructor or a method, brackets and all. */
  private static String arguments(java.lang.reflect.Type[] args) {
    List<String> each = new ArrayList<>();
    for (java.lang.reflect.Type arg : args) {
      each.add(arg.getTypeName());
    }
    return "(" + String.join(", ", each) + ")";
  }

  /** What a member declares it throws, or nothing at all. */
  private static String thrown(java.lang.reflect.Type[] exceptions) {
    if (exceptions.length == 0) {
      return "";
    }
    List<String> each = new ArrayList<>();
    for (java.lang.reflect.Type exception : exceptions) {
      each.add(exception.getTypeName());
    }
    return " throws " + String.join(", ", each);
  }

  /** The type parameters a member declares, with a space after them. */
  private static String generics(TypeVariable<?>[] vars) {
    String written = parameters(vars);
    return written.isEmpty() ? "" : written + " ";
  }

  /** The type parameters a type declares, or nothing at all. */
  private static String parameters(Class<?> type) {
    return parameters(type.getTypeParameters());
  }

  /** The type parameters a type or a member declares, or nothing at all. */
  private static String parameters(TypeVariable<?>[] vars) {
    if (vars.length == 0) {
      return "";
    }
    List<String> each = new ArrayList<>();
    for (TypeVariable<?> var : vars) {
      StringBuilder one = new StringBuilder(var.getName());
      List<String> bounds = new ArrayList<>();
      for (java.lang.reflect.Type bound : var.getBounds()) {
        if (bound != Object.class) {
          bounds.add(bound.getTypeName());
        }
      }
      if (!bounds.isEmpty()) {
        one.append(" extends ").append(String.join(" & ", bounds));
      }
      each.add(one.toString());
    }
    return "<" + String.join(", ", each) + ">";
  }

  /** Whether a member is one a caller outside this module can name. */
  private static boolean published(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  /**
   * The exported types, read off the classes this module was compiled to.
   *
   * <p>Reading the directory rather than a list of names is the point: a type
   * somebody adds turns up here without anybody remembering to add it, which
   * is the failure mode of every hand-kept inventory.
   */
  private static List<Class<?>> types() throws IOException {
    Path classes = Paths.get("target", "classes");
    assertTrue(
        Files.isDirectory(classes),
        classes.toAbsolutePath().normalize() + " is missing: the build writes it before the tests");
    List<Class<?>> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(classes)) {
      for (Path each : walk.toList()) {
        String name = classes.relativize(each).toString();
        if (!name.endsWith(".class")) {
          continue;
        }
        name =
            name.substring(0, name.length() - ".class".length())
                .replace(java.io.File.separatorChar, '.')
                .replace('/', '.');
        if (name.equals("module-info") || name.endsWith("package-info")) {
          continue;
        }
        String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
        if (!EXPORTED.contains(pkg)) {
          continue;
        }
        Class<?> type;
        try {
          type = Class.forName(name, false, SurfaceTest.class.getClassLoader());
        } catch (ClassNotFoundException cannot) {
          throw new IllegalStateException(name + " is on disk and not loadable", cannot);
        }
        if (published(type.getModifiers())) {
          found.add(type);
        }
      }
    }
    assertTrue(found.size() > 20, "found " + found.size() + " exported types, which is too few");
    found.sort((a, b) -> a.getName().compareTo(b.getName()));
    return found;
  }
}
