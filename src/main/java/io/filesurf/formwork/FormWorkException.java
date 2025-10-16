package io.filesurf.formwork;

/**
 * Exception thrown when LLM construction operations fail. This exception indicates that the LLM was
 * unable to construct a valid object of the requested type after exhausting all retry attempts.
 */
public class FormWorkException extends RuntimeException {

  /**
   * Constructs a new FormWorkException with the specified detail message.
   *
   * @param message the detail message
   */
  public FormWorkException(String message) {
    super(message);
  }

  /**
   * Constructs a new FormWorkException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public FormWorkException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new FormWorkException with the specified cause.
   *
   * @param cause the cause of this exception
   */
  public FormWorkException(Throwable cause) {
    super(cause);
  }
}
