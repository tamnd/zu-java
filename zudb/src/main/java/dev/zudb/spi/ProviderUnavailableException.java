package dev.zudb.spi;

/**
 * A provider cannot run on this JVM, which is a fact about the JVM rather
 * than a failure of the program.
 *
 * <p>A JDK too old for the API a provider needs, native access not granted, a
 * shim that is not on the library path, a libzu missing a symbol this client
 * calls. The API module catches this, tries the next provider, and puts every
 * reason it collected into one message if there is none left, because the
 * user who has to fix it wants to see all of them at once and not the first
 * one over and over.
 */
public class ProviderUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Says why, in a sentence a user can act on.
   *
   * @param message what is missing and, where there is one, the flag or the
   *     property that supplies it
   */
  public ProviderUnavailableException(String message) {
    super(message);
  }

  /**
   * The same, keeping what went wrong underneath.
   *
   * @param message what is missing
   * @param cause what the JVM said
   */
  public ProviderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
