package org.docling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inserts {@code ---- Page N ----} markers into docling's Markdown output.
 *
 * <p>The docling CLI cannot emit page boundaries into Markdown directly, but its {@code --to json}
 * output records, for every text item, the page it came from (its {@code prov[].page_no}). This
 * helper takes the canonical Markdown plus that JSON and re-discovers the page boundaries by
 * matching, for each page, the first piece of its text against the Markdown — then inserts a marker
 * line just before it.
 *
 * <p>Matching is best-effort and works for text-bearing documents (PDFs, Office files, …). Pages
 * that contain no locatable text of their own (e.g. a spreadsheet sheet that is a single table) are
 * left unmarked rather than risk placing a marker in the wrong spot.
 */
final class PageMarkers {

  private static final Logger log = LoggerFactory.getLogger(PageMarkers.class);

  /** Format of an inserted marker; {@code %d} is the (1-based) page number. */
  private static final String MARKER_FORMAT = "---- Page %d ----";

  /** Running headers/footers repeat on every page, so they make poor (ambiguous) anchors. */
  private static final Set<String> SKIP_LABELS = Set.of("page_header", "page_footer");

  /** A backslash-escaped Markdown punctuation character, e.g. {@code \&#42;} or {@code \_}. */
  private static final Pattern MD_ESCAPE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+.!-])");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Anchor on a short, distinctive prefix of each item's text. */
  private static final int ANCHOR_LENGTH = 40;

  /** Ignore anchors shorter than this — too generic to match reliably. */
  private static final int MIN_ANCHOR_LENGTH = 6;

  private PageMarkers() {}

  /**
   * Returns {@code markdown} with {@code ---- Page N ----} markers inserted at page boundaries.
   *
   * @param markdown the Markdown produced by {@code docling --to md}
   * @param doclingJson the JSON produced by {@code docling --to json} for the same document
   */
  static String insert(String markdown, String doclingJson) {
    // page -> ordered list of normalized anchor candidates drawn from that page's text items
    TreeMap<Integer, List<String>> candidates = new TreeMap<>();
    Integer firstPage = null;

    Object root = Json.parse(doclingJson);
    for (Object item : asList(value(root, "texts"))) {
      Map<String, Object> text = asMap(item);
      List<Object> prov = asList(text.get("prov"));
      if (prov.isEmpty()) {
        continue;
      }
      int page = ((Number) asMap(prov.get(0)).get("page_no")).intValue();
      firstPage = (firstPage == null) ? page : Math.min(firstPage, page);

      if (SKIP_LABELS.contains(text.get("label"))) {
        continue;
      }
      String anchor = anchor(text.get("text"));
      if (anchor != null) {
        candidates.computeIfAbsent(page, p -> new ArrayList<>()).add(anchor);
      }
    }

    if (firstPage == null) {
      log.debug("No page provenance found in docling JSON; leaving Markdown unmarked.");
      return markdown;
    }

    List<String> lines = List.of(markdown.split("\n", -1));
    List<String> normalized = new ArrayList<>(lines.size());
    for (String line : lines) {
      normalized.add(normalize(line));
    }

    // For each page after the first, find the first line containing one of its anchors, scanning
    // forward so the markers stay in document order. The result maps a line index to the page whose
    // marker should be inserted just before it.
    Map<Integer, Integer> markerAtLine = new HashMap<>();
    int cursor = 0;
    for (Map.Entry<Integer, List<String>> entry : candidates.tailMap(firstPage, false).entrySet()) {
      int line = locate(normalized, cursor, entry.getValue());
      if (line >= 0) {
        markerAtLine.put(line, entry.getKey());
        cursor = line + 1;
      } else {
        log.debug("Could not locate a Markdown anchor for page {}; it will be unmarked.", entry.getKey());
      }
    }

    StringBuilder out = new StringBuilder(markdown.length() + markerAtLine.size() * 24 + 32);
    out.append(String.format(MARKER_FORMAT, firstPage)).append("\n\n");
    for (int i = 0; i < lines.size(); i++) {
      Integer page = markerAtLine.get(i);
      if (page != null) {
        out.append('\n').append(String.format(MARKER_FORMAT, page)).append("\n\n");
      }
      out.append(lines.get(i));
      if (i < lines.size() - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  /** Index of the first line at or after {@code from} that contains any of the anchors, or -1. */
  private static int locate(List<String> normalizedLines, int from, List<String> anchors) {
    for (String anchor : anchors) {
      for (int i = from; i < normalizedLines.size(); i++) {
        if (normalizedLines.get(i).contains(anchor)) {
          return i;
        }
      }
    }
    return -1;
  }

  /** Normalized anchor prefix for an item's text, or {@code null} if it is too short to be useful. */
  private static String anchor(Object text) {
    if (!(text instanceof String s)) {
      return null;
    }
    String n = normalize(s);
    if (n.length() < MIN_ANCHOR_LENGTH) {
      return null;
    }
    return n.length() <= ANCHOR_LENGTH ? n : n.substring(0, ANCHOR_LENGTH);
  }

  /** Strips Markdown escapes, collapses whitespace and lower-cases, so JSON text matches Markdown. */
  private static String normalize(String s) {
    String unescaped = MD_ESCAPE.matcher(s).replaceAll("$1");
    return WHITESPACE.matcher(unescaped).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
  }

  private static Object value(Object node, String key) {
    return asMap(node).get(key);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object node) {
    return node instanceof Map ? (Map<String, Object>) node : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object node) {
    return node instanceof List ? (List<Object>) node : List.of();
  }
}
