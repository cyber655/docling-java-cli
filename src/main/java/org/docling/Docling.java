package org.docling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thin, embeddable Java wrapper around the {@code docling} command-line tool.
 *
 * <p>It does not reimplement conversion — it shells out to the {@code docling} executable, which
 * must be installed and on your {@code PATH} (e.g. {@code pip install docling}).
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * Docling docling = Docling.builder().build();
 * String markdown = docling.convert("path/to/file.pdf");
 * }</pre>
 *
 * <p>Instances are immutable and safe to reuse across conversions.
 */
public final class Docling {

  private static final Logger log = LoggerFactory.getLogger(Docling.class);

  /** How long to wait for the output-draining thread to finish after the process has ended. */
  private static final long SHUTDOWN_GRACE_MILLIS = 2_000;

  /** Markdown image whose target is a base64 data URI, e.g. {@code ![alt](data:image/png;base64,…)}. */
  private static final Pattern MARKDOWN_BASE64_IMAGE =
      Pattern.compile("!\\[[^\\]]*\\]\\(\\s*data:image/[^;]+;base64,[^)]*\\)");

  /** HTML {@code <img>} whose {@code src} is a base64 data URI. */
  private static final Pattern HTML_BASE64_IMAGE =
      Pattern.compile(
          "<img\\b[^>]*src=[\"']data:image/[^;]+;base64,[^\"']*[\"'][^>]*>",
          Pattern.CASE_INSENSITIVE);

  private final String executable;
  private final Duration timeout;

  private Docling(Builder builder) {
    this.executable = builder.executable;
    this.timeout = builder.timeout;
  }

  /** Creates a new builder with default settings ({@code docling} on the PATH, 600s timeout). */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Converts the given source (local file path or URL) to Markdown and returns it as a string.
   *
   * @param source path or URL of the document to convert
   * @return the generated Markdown
   * @throws DoclingException if docling cannot be launched, times out, or exits non-zero
   */
  public String convert(String source) {
    return convert(source, false);
  }

  /**
   * Converts the given source (local file path or URL) to Markdown and returns it as a string.
   *
   * @param source path or URL of the document to convert
   * @param removeBase64Images when {@code true}, strips images embedded as base64 data URIs from the
   *     generated Markdown (both {@code ![...](data:image/...)} and {@code <img src="data:image/...">}
   *     forms)
   * @return the generated Markdown
   * @throws DoclingException if docling cannot be launched, times out, or exits non-zero
   */
  public String convert(String source, boolean removeBase64Images) {
    Path tempDir;
    try {
      tempDir = Files.createTempDirectory("docling-java-");
    } catch (IOException e) {
      throw new DoclingException("Failed to create a temporary output directory.", e);
    }
    try {
      Path markdown = convertToDir(source, tempDir);
      String content = Files.readString(markdown, StandardCharsets.UTF_8);
      return removeBase64Images ? stripBase64Images(content) : content;
    } catch (IOException e) {
      throw new DoclingException("Failed to read the generated Markdown.", e);
    } finally {
      deleteRecursively(tempDir);
    }
  }

  /** Converts a local file to Markdown and returns it as a string. */
  public String convert(Path source) {
    return convert(source.toString(), false);
  }

  /**
   * Converts a local file to Markdown and returns it as a string.
   *
   * @param source path of the document to convert
   * @param removeBase64Images when {@code true}, strips base64-embedded images from the output
   */
  public String convert(Path source, boolean removeBase64Images) {
    return convert(source.toString(), removeBase64Images);
  }

  /**
   * Converts in-memory document bytes to Markdown and returns it as a string.
   *
   * <p>docling infers the input format from the file extension, so an {@code extension} matching the
   * data is required. The bytes are written to a temporary file (deleted afterwards) before being
   * handed to docling.
   *
   * @param data the raw document bytes (e.g. the contents of a PDF or DOCX)
   * @param extension the file extension describing the data, with or without a leading dot (e.g.
   *     {@code "pdf"}, {@code ".docx"})
   * @return the generated Markdown
   * @throws DoclingException if docling cannot be launched, times out, or exits non-zero
   */
  public String convert(byte[] data, String extension) {
    return convert(data, extension, false);
  }

  /**
   * Converts in-memory document bytes to Markdown and returns it as a string.
   *
   * <p>docling infers the input format from the file extension, so an {@code extension} matching the
   * data is required. The bytes are written to a temporary file (deleted afterwards) before being
   * handed to docling.
   *
   * @param data the raw document bytes (e.g. the contents of a PDF or DOCX)
   * @param extension the file extension describing the data, with or without a leading dot (e.g.
   *     {@code "pdf"}, {@code ".docx"})
   * @param removeBase64Images when {@code true}, strips base64-embedded images from the output
   * @return the generated Markdown
   * @throws DoclingException if docling cannot be launched, times out, or exits non-zero
   */
  public String convert(byte[] data, String extension, boolean removeBase64Images) {
    if (data == null) {
      throw new IllegalArgumentException("data must not be null");
    }
    if (extension == null || extension.isBlank()) {
      throw new IllegalArgumentException(
          "extension must not be blank (docling infers the format from it, e.g. \"pdf\")");
    }
    String suffix = extension.startsWith(".") ? extension : "." + extension;

    Path tempFile;
    try {
      tempFile = Files.createTempFile("docling-java-", suffix);
      Files.write(tempFile, data);
    } catch (IOException e) {
      throw new DoclingException("Failed to write input bytes to a temporary file.", e);
    }
    try {
      return convert(tempFile.toString(), removeBase64Images);
    } finally {
      deleteRecursively(tempFile);
    }
  }

  /**
   * Converts the given source to Markdown, writing the {@code .md} file into {@code outputDir}.
   *
   * @param source path or URL of the document to convert
   * @param outputDir directory the Markdown file is written to (created if missing)
   * @return the path of the generated Markdown file
   * @throws DoclingException if docling cannot be launched, times out, or exits non-zero
   */
  public Path convertToDir(String source, Path outputDir) {
    try {
      Files.createDirectories(outputDir);
    } catch (IOException e) {
      throw new DoclingException("Failed to create output directory " + outputDir + ".", e);
    }

    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add(source);
    command.add("--to");
    command.add("md");
    command.add("--output");
    command.add(outputDir.toString());

    ProcessResult result = run(command);
    if (result.exitCode() != 0) {
      String detail = result.output().isBlank() ? "" : "\n" + result.output().strip();
      throw new DoclingException(
          "docling exited with code " + result.exitCode() + " for source: " + source + detail);
    }
    return findMarkdown(outputDir);
  }

  private ProcessResult run(List<String> command) {
    log.debug("Running docling: {}", String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new DoclingException(
          "Failed to launch '" + executable + "'. Is docling installed and on your PATH? "
              + "(pip install docling).",
          e);
    }

    // Drain the process output on a separate thread so a chatty docling can never block on a full
    // pipe buffer while we wait for it to finish. Each line is logged at DEBUG and captured so it
    // can be surfaced in the exception message on failure.
    StringBuilder captured = new StringBuilder();
    Thread gobbler = startOutputGobbler(process, captured);

    try {
      if (!timeout.isZero()) {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          gobbler.join(SHUTDOWN_GRACE_MILLIS);
          throw new DoclingException("docling timed out after " + timeout.toSeconds() + "s.");
        }
      } else {
        process.waitFor();
      }
      gobbler.join(SHUTDOWN_GRACE_MILLIS);
      return new ProcessResult(process.exitValue(), captured.toString());
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new DoclingException("Interrupted while waiting for docling.", e);
    }
  }

  private static Thread startOutputGobbler(Process process, StringBuilder sink) {
    Thread thread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  log.debug("[docling] {}", line);
                  sink.append(line).append(System.lineSeparator());
                }
              } catch (IOException e) {
                log.debug("Failed to read docling output stream", e);
              }
            },
            "docling-output");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Outcome of a docling process invocation: its exit code and the combined stdout/stderr. */
  private record ProcessResult(int exitCode, String output) {}

  private static String stripBase64Images(String markdown) {
    String withoutMarkdown = MARKDOWN_BASE64_IMAGE.matcher(markdown).replaceAll("");
    return HTML_BASE64_IMAGE.matcher(withoutMarkdown).replaceAll("");
  }

  private static Path findMarkdown(Path outputDir) {
    try (Stream<Path> files = Files.list(outputDir)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".md"))
          .findFirst()
          .orElseThrow(
              () ->
                  new DoclingException(
                      "Conversion finished but no Markdown file was produced in " + outputDir));
    } catch (IOException e) {
      throw new DoclingException("Failed to inspect output directory " + outputDir + ".", e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException | UncheckedIOException e) {
      // Best-effort cleanup of a temp directory; ignore failures.
    }
  }

  /** Fluent builder for {@link Docling}. */
  public static final class Builder {
    private String executable = "docling";
    private Duration timeout = Duration.ofSeconds(600);

    private Builder() {}

    /** Sets the name or path of the docling executable (default: {@code docling}). */
    public Builder executable(String executable) {
      if (executable == null || executable.isBlank()) {
        throw new IllegalArgumentException("executable must not be blank");
      }
      this.executable = executable;
      return this;
    }

    /** Sets the maximum time to wait for a conversion ({@code Duration.ZERO} = no limit). */
    public Builder timeout(Duration timeout) {
      if (timeout == null || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must not be null or negative");
      }
      this.timeout = timeout;
      return this;
    }

    public Docling build() {
      return new Docling(this);
    }
  }
}
