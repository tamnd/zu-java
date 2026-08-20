package dev.zudb.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * The off-heap space one thread needs for the length of one call, reused.
 *
 * <p>Every call across this boundary needs somewhere for the library to write
 * its out-parameters, and most of them need somewhere to put a string in the
 * encoding C reads. The obvious way to get that is a confined {@link Arena}
 * per call, and the obvious way costs a malloc and a free on a path that is
 * otherwise a handful of instructions, which shows up the moment anybody binds
 * a parameter in a loop.
 *
 * <p>So: one block per thread, allocated once, handed out by bumping a pointer
 * and reclaimed by setting that pointer back to nought at the top of the next
 * call. Nothing here outlives the call that asked for it, which is what makes
 * that safe, and the out-parameter slots are read before the call returns.
 * The block belongs to an automatic arena, so a thread that goes away takes
 * its block with it without anybody having to close anything.
 */
final class Scratch {

  /** Where the first out-parameter goes. */
  static final long OUT = 0;

  /** Where a length out-parameter goes. */
  static final long LEN = 8;

  /** Where an error out-parameter goes. */
  static final long ERR = 16;

  /** Three more slots, for the calls that write a triple. */
  static final long A = 24;

  static final long B = 32;

  static final long C = 40;

  private static final long SLOTS = 64;

  /**
   * {@code sizeof(zu_config)} as this client's header declares it: three
   * {@code size_t} and an {@code int32_t}, rounded up to the alignment.
   *
   * <p>This is written into the struct's first field rather than read back out
   * of {@code zu_config_init}, and on purpose. The struct is versioned so that
   * a library newer than the caller reads only the fields the caller says it
   * has, and letting the library tell us how long our own buffer is would
   * invert that: a library that grew the struct would write a size past the
   * end of what we allocated and then read there.
   */
  static final long CONFIG = 32;

  private static final ThreadLocal<Scratch> LOCAL = ThreadLocal.withInitial(Scratch::new);

  private final Arena arena = Arena.ofAuto();
  private final MemorySegment slots = arena.allocate(SLOTS, 8);
  private final MemorySegment config = arena.allocate(CONFIG, 8);
  private MemorySegment block = arena.allocate(512, 8);
  private long used;

  private Scratch() {}

  /**
   * This thread's scratch, with its bump pointer wound back.
   *
   * @return the scratch, whose previous contents are now free space
   */
  static Scratch get() {
    Scratch s = LOCAL.get();
    s.used = 0;
    return s;
  }

  /**
   * The fixed block the out-parameters live in, at the offsets named above.
   *
   * @return the block, whose contents are whatever the last call left
   */
  MemorySegment slots() {
    return slots;
  }

  /**
   * The {@code zu_config} this thread fills in and passes by pointer.
   *
   * @return the block, zeroed and with its size field set
   */
  MemorySegment config() {
    config.fill((byte) 0);
    return config;
  }

  /**
   * A string as UTF-8, without a terminator, since every call that takes one
   * takes its length beside it.
   *
   * @param s the string
   * @return a segment holding exactly its bytes
   */
  MemorySegment utf8(String s) {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    MemorySegment out = alloc(bytes.length);
    MemorySegment.copy(bytes, 0, out, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
    return out;
  }

  private MemorySegment alloc(long bytes) {
    if (used + bytes > block.byteSize()) {
      // The old block stays alive as long as anything handed out of it is
      // reachable, so growing mid-call cannot pull the ground out from under
      // a segment already passed to a native function.
      long size = Math.max(block.byteSize() * 2, bytes);
      block = arena.allocate(size, 8);
      used = 0;
    }
    MemorySegment out = block.asSlice(used, bytes);
    used += bytes;
    return out;
  }
}
