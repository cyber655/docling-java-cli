package org.docling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PageMarkersTest {

  private static String resource(String name) throws IOException {
    try (InputStream in = PageMarkersTest.class.getResourceAsStream("/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void insertsAMarkerForEveryPageInOrder() throws IOException {
    String md = resource("multi_page.md");
    String json = resource("multi_page.json");

    String marked = PageMarkers.insert(md, json);

    // The fixture (multi_page) has five text-bearing pages.
    List<Integer> pages = new ArrayList<>();
    Matcher m = Pattern.compile("^---- Page (\\d+) ----$", Pattern.MULTILINE).matcher(marked);
    while (m.find()) {
      pages.add(Integer.parseInt(m.group(1)));
    }
    assertEquals(List.of(1, 2, 3, 4, 5), pages, "expected one in-order marker per page");
  }

  @Test
  void firstMarkerIsAtTheTop() throws IOException {
    String marked = PageMarkers.insert(resource("multi_page.md"), resource("multi_page.json"));
    assertTrue(marked.startsWith("---- Page 1 ----\n"), "page 1 marker should head the document");
  }

  @Test
  void preservesTheOriginalMarkdownContent() throws IOException {
    String md = resource("multi_page.md");
    String marked = PageMarkers.insert(md, resource("multi_page.json"));
    // Every original non-blank line must still be present once the markers are removed.
    String stripped =
        marked.replaceAll("(?m)^---- Page \\d+ ----$", "").replaceAll("\n{2,}", "\n");
    for (String line : md.split("\n")) {
      if (!line.isBlank()) {
        assertTrue(stripped.contains(line), "lost original line: " + line);
      }
    }
  }

  @Test
  void leavesMarkdownUnchangedWhenJsonHasNoPages() {
    String md = "# Title\n\nSome text.\n";
    assertEquals(md, PageMarkers.insert(md, "{\"texts\": []}"));
  }

  @Test
  void parsesEscapesAndUnicodeInJson() {
    Object root = Json.parse("{\"a\": \"line\\none\\u0041\", \"b\": [1, 2.5, true, null]}");
    @SuppressWarnings("unchecked")
    var map = (java.util.Map<String, Object>) root;
    assertEquals("line\noneA", map.get("a"));
    assertFalse(((List<?>) map.get("b")).isEmpty());
  }
}
