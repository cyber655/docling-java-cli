package org.docling;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DoclingBuilderTest {

  @Test
  void buildsWithDefaults() {
    assertDoesNotThrow(() -> Docling.builder().build());
  }

  @Test
  void acceptsZeroTimeoutAsNoLimit() {
    assertDoesNotThrow(() -> Docling.builder().timeout(Duration.ZERO).build());
  }

  @Test
  void rejectsBlankExecutable() {
    assertThrows(IllegalArgumentException.class, () -> Docling.builder().executable("  "));
    assertThrows(IllegalArgumentException.class, () -> Docling.builder().executable(null));
  }

  @Test
  void rejectsNegativeOrNullTimeout() {
    assertThrows(
        IllegalArgumentException.class, () -> Docling.builder().timeout(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> Docling.builder().timeout(null));
  }
}
