package io.filesurf.formwork;

public class FormWorkException extends RuntimeException {

  public FormWorkException(String message) {
    super(message);
  }

  public FormWorkException(String message, Throwable cause) {
    super(message, cause);
  }

  public FormWorkException(Throwable cause) {
    super(cause);
  }
}
