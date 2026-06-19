# docling-java-cli

A tiny Java/Maven wrapper around the [`docling`](https://github.com/docling-project/docling)
tool to convert PDFs (and other supported inputs) to Markdown. It works both as an
**embeddable library** (a fluent `Docling` API you call from your own code) and as a
standalone **CLI**.

It does not reimplement conversion — it shells out to the `docling` executable, so that
tool must be installed and reachable on your `PATH`.

## Prerequisites

**1. Install docling with pip** (Python 3.9+):

```bash
pip install docling
```

**2. Make sure `docling` is on your `PATH`.** pip installs the executable into a `bin`
(Linux/macOS) or `Scripts` (Windows) directory. If `docling --version` works in a fresh
terminal, you're done. Otherwise add that directory to your `PATH`:

```zsh
# zsh (~/.zshrc) — default on macOS
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

```bash
# bash (~/.bashrc or ~/.bash_profile)
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

```fish
# fish (~/.config/fish/config.fish)
fish_add_path $HOME/.local/bin
```

```powershell
# Windows PowerShell (persists for the current user)
[Environment]::SetEnvironmentVariable(
  "Path", "$env:APPDATA\Python\Scripts;$env:Path", "User")
```

> Tip: run `pip show -f docling` or `python -m site --user-base` to find exactly where the
> executable landed. If you'd rather not touch `PATH`, point the library/CLI straight at the
> binary with `.executable("/full/path/to/docling")` or `--docling-bin /full/path/to/docling`.

## Get it from Maven Central

Released as `io.github.cyber655:docling-java-cli` on
[Maven Central](https://central.sonatype.com/artifact/io.github.cyber655/docling-java-cli).

```xml
<dependency>
    <groupId>io.github.cyber655</groupId>
    <artifactId>docling-java-cli</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Use it as a library

Build a `Docling` instance with the fluent builder and call `convert`. By default the
`docling` executable is looked up on the `PATH` and conversions time out after 600s.

```java
import org.docling.Docling;

Docling docling = Docling.builder().build();

// Convert a local file or a URL and get the Markdown back as a String:
String markdown = docling.convert("path/to/file.pdf");
String fromUrl  = docling.convert("https://arxiv.org/pdf/2408.09869");
```

Customize the executable and timeout:

```java
import java.time.Duration;
import org.docling.Docling;

Docling docling = Docling.builder()
    .executable("/usr/local/bin/docling")  // default: "docling" (resolved on PATH)
    .timeout(Duration.ofMinutes(5))         // default: 600s; Duration.ZERO = no limit
    .build();

String markdown = docling.convert("path/to/file.pdf");
```

### Convert from bytes

If you already have the document in memory (e.g. an upload or a blob from a database), pass
the raw bytes plus an extension so docling can infer the format. The bytes are written to a
temporary file (deleted automatically) before conversion:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import org.docling.Docling;

Docling docling = Docling.builder().build();

byte[] pdfBytes = Files.readAllBytes(Path.of("path/to/file.pdf"));
String markdown = docling.convert(pdfBytes, "pdf");   // extension may be "pdf" or ".pdf"
```

### Strip base64-embedded images

docling can embed images inline as base64 data URIs, which bloats the Markdown. Pass an
optional `removeBase64Images` flag to drop them (works for file/URL sources and for bytes).
It removes both `![alt](data:image/...;base64,...)` and `<img src="data:image/...;base64,...">`:

```java
String clean = docling.convert("path/to/file.pdf", true);     // from a path or URL
String fromBytes = docling.convert(pdfBytes, "pdf", true);     // from bytes
```

The flag defaults to `false`, so the existing single-argument calls keep returning the full
Markdown unchanged.

### Keep the file on disk

If you'd rather keep the generated `.md` file on disk, write it to a directory and get its
path back:

```java
import java.nio.file.Path;

Path mdFile = docling.convertToDir("path/to/file.pdf", Path.of("./out"));
```

A `Docling` instance is immutable and safe to reuse across conversions. On any failure
(executable not found, timeout, or a non-zero docling exit) it throws an unchecked
`org.docling.DoclingException`.

### API

| Method | Description |
| --- | --- |
| `Docling.builder()` | Start configuring an instance |
| `.executable(String)` | Name or path of the docling executable (default `docling`) |
| `.timeout(Duration)` | Max wait per conversion (default 600s; `Duration.ZERO` = no limit) |
| `.build()` | Create the immutable `Docling` instance |
| `Docling#convert(String\|Path [, boolean])` | Convert a source (path or URL) and return the Markdown as a `String`; optional flag strips base64 images |
| `Docling#convert(byte[], String [, boolean])` | Convert in-memory bytes (with a format extension); optional flag strips base64 images |
| `Docling#convertToDir(String, Path)` | Convert a source, write the `.md` into a dir, return its `Path` |

## Use it as a CLI

Download the runnable jar:

```bash
mvn dependency:copy \
  -Dartifact=io.github.cyber655:docling-java-cli:1.0.0:jar \
  -DoutputDirectory=.
```

```bash
# from a local file
java -jar docling-java-cli-1.0.0.jar path/to/file.pdf

# choose an output directory
java -jar docling-java-cli-1.0.0.jar path/to/file.pdf --output ./out

# from a URL
java -jar docling-java-cli-1.0.0.jar https://arxiv.org/pdf/2408.09869 -o ./out
```

On success it prints the absolute path of the generated `.md` file to stdout. The
underlying invocation is:

```bash
docling <SOURCE> --to md --output <DIR>
```

### Options

| Option | Description | Default |
| --- | --- | --- |
| `SOURCE` | Path or URL of the PDF to convert (required) | — |
| `-o`, `--output DIR` | Output directory for the Markdown | `.` |
| `--docling-bin BIN` | Name/path of the docling executable | `docling` |
| `--timeout SECONDS` | Max time to wait (`0` = no limit) | `600` |
| `-h`, `--help` | Show help | — |
| `-V`, `--version` | Show version | — |

### Exit codes

- `0` — success
- non-zero — conversion failed (message printed to stderr)

## Build from source

Requires JDK 25+.

```bash
cd docling-java-cli
mvn package
```

This produces a self-contained jar at `target/docling-cli.jar`.

### Releasing

GPG-sign and publish to Maven Central via the Sonatype Central Portal:

```bash
mvn -Prelease deploy
```

Credentials (Central user token) and the GPG passphrase are read from
`~/.m2/settings.xml` (server id `central`, profile id `release`).

## License

docling-java-cli is under MIT license.
It invokes the separate `docling` tool; for model usage, refer to the
model licenses in the docling packages.
