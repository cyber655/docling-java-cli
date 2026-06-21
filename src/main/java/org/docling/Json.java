package org.docling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON parser, sufficient for reading docling's {@code --to json} output.
 *
 * <p>{@link #parse(String)} returns a tree of plain Java values: {@link Map}{@code <String,Object>}
 * for objects, {@link List}{@code <Object>} for arrays, {@link String}, {@link Double},
 * {@link Boolean} and {@code null}. It is deliberately minimal — just enough to walk the document
 * structure — and is not meant as a general-purpose JSON library.
 */
final class Json {

  private final String src;
  private int pos;

  private Json(String src) {
    this.src = src;
  }

  /** Parses a JSON document into a tree of {@code Map}/{@code List}/{@code String}/… values. */
  static Object parse(String src) {
    Json json = new Json(src);
    json.skipWhitespace();
    Object value = json.readValue();
    json.skipWhitespace();
    if (json.pos != src.length()) {
      throw new IllegalArgumentException("Trailing content at position " + json.pos);
    }
    return value;
  }

  private Object readValue() {
    char c = peek();
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't', 'f' -> readBoolean();
      case 'n' -> readNull();
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<>();
    expect('{');
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, readValue());
      skipWhitespace();
      char c = next();
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("',' or '}'");
      }
    }
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<>();
    expect('[');
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(readValue());
      skipWhitespace();
      char c = next();
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("',' or ']'");
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        char e = next();
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw error("a valid escape sequence");
        }
      } else {
        sb.append(c);
      }
    }
  }

  private Boolean readBoolean() {
    if (src.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (src.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    throw error("'true' or 'false'");
  }

  private Object readNull() {
    if (src.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    throw error("'null'");
  }

  private Double readNumber() {
    int start = pos;
    while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
      pos++;
    }
    if (pos == start) {
      throw error("a value");
    }
    return Double.parseDouble(src.substring(start, pos));
  }

  private void skipWhitespace() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= src.length()) {
      throw error("more input");
    }
    return src.charAt(pos);
  }

  private char next() {
    return src.charAt(pos++);
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("'" + c + "'");
    }
  }

  private IllegalArgumentException error(String expected) {
    return new IllegalArgumentException("Expected " + expected + " at position " + pos);
  }
}
