package org.docling.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.docling.Docling;
import org.docling.DoclingException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command-line front-end for the {@link Docling} library.
 *
 * <p>It shells out to {@code docling <source> --to md --output <dir>} and reports where the
 * generated Markdown was written. The {@code docling} executable must be installed and on the PATH
 * (e.g. {@code pip install docling}).
 */
@Command(
    name = "docling-cli",
    mixinStandardHelpOptions = true,
    version = "docling-java-cli 1.0.0",
    description = "Convert a PDF (or other supported input) to Markdown via docling.")
public class DoclingCli implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "SOURCE", description = "Path or URL of the PDF to convert.")
  private String source;

  @Option(
      names = {"-o", "--output"},
      paramLabel = "DIR",
      description = "Output directory for the generated Markdown (default: current directory).")
  private Path outputDir = Path.of(".");

  @Option(
      names = {"--docling-bin"},
      paramLabel = "BIN",
      description = "Name or path of the docling executable (default: ${DEFAULT-VALUE}).")
  private String doclingBin = "docling";

  @Option(
      names = {"--timeout"},
      paramLabel = "SECONDS",
      description = "Maximum time to wait for conversion (default: ${DEFAULT-VALUE}, 0 = no limit).")
  private long timeoutSeconds = 600;

  @Override
  public Integer call() {
    Docling docling =
        Docling.builder()
            .executable(doclingBin)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();

    try {
      Path markdown = docling.convertToDir(source, outputDir);
      System.out.println(markdown.toAbsolutePath());
      return 0;
    } catch (DoclingException e) {
      System.err.println(e.getMessage());
      return 1;
    }
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new DoclingCli()).execute(args));
  }
}
