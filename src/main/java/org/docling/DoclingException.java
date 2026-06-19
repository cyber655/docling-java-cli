package org.docling;

/** Thrown when a docling conversion fails to launch, times out, or exits non-zero. */
public class DoclingException extends RuntimeException {

  public DoclingException(String message) {
    super(message);
  }

  public DoclingException(String message, Throwable cause) {
    super(message, cause);
  }
}
