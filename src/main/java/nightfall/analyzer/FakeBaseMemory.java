package nightfall.analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Lokale, vom Spieler trainierte Fake-Base-Muster. Es werden keine Welt- oder Spielerdaten gespeichert. */
public final class FakeBaseMemory {
  private static final int MAX_PATTERNS = 32;
  private static final List<BaseFingerprint> PATTERNS = new ArrayList<>();
  private static boolean loaded;
  private FakeBaseMemory() {}

  private static Path file() {
    return FabricLoader.getInstance().getConfigDir().resolve("nightfall-fake-base-patterns.txt");
  }

  private static synchronized void load() {
    if (loaded) return;
    loaded = true;
    Path path = file();
    if (!Files.isRegularFile(path)) return;
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String value = line.trim();
        if (value.isEmpty() || value.startsWith("#")) continue;
        String[] parts = value.split(",");
        if (parts.length != 11) continue;
        try {
          int[] v = new int[11];
          for (int i = 0; i < v.length; i++) v[i] = Math.max(0, Math.min(1_000_000, Integer.parseInt(parts[i].trim())));
          BaseFingerprint fp = new BaseFingerprint(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10]);
          if (fp.canLearn() && PATTERNS.size() < MAX_PATTERNS) PATTERNS.add(fp);
        } catch (NumberFormatException ignored) { /* Ungueltige Zeile ueberspringen. */ }
      }
    } catch (IOException ignored) { /* Ohne gespeicherte Muster normal weiterarbeiten. */ }
  }

  private static synchronized boolean save() {
    load();
    List<String> lines = new ArrayList<>();
    lines.add("# Nightfall: lokal gelernte Fake-Base-Muster (11 Zahlen pro Zeile)");
    for (BaseFingerprint fp : PATTERNS) {
      int[] v = fp.values();
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < v.length; i++) { if (i > 0) line.append(','); line.append(v[i]); }
      lines.add(line.toString());
    }
    try {
      Files.createDirectories(file().getParent());
      Files.write(file(), lines, StandardCharsets.UTF_8);
      return true;
    } catch (IOException ignored) { return false; }
  }

  public static synchronized int matchPercent(BaseFingerprint fp) {
    load();
    int best = 0;
    for (BaseFingerprint known : PATTERNS) best = Math.max(best, known.similarity(fp));
    return best >= 88 ? best : 0;
  }

  /** True when a new pattern was saved; near-duplicates are not stored twice. */
  public static synchronized boolean learn(BaseFingerprint fp) {
    load();
    if (fp == null || !fp.canLearn() || PATTERNS.size() >= MAX_PATTERNS) return false;
    for (BaseFingerprint known : PATTERNS) if (known.similarity(fp) >= 92) return false;
    PATTERNS.add(fp);
    if (save()) return true;
    PATTERNS.remove(PATTERNS.size() - 1);
    return false;
  }

  public static synchronized int size() { load(); return PATTERNS.size(); }
  public static synchronized boolean remove(int oneBasedIndex) {
    load();
    if (oneBasedIndex < 1 || oneBasedIndex > PATTERNS.size()) return false;
    BaseFingerprint removed = PATTERNS.remove(oneBasedIndex - 1);
    if (save()) return true;
    PATTERNS.add(oneBasedIndex - 1, removed);
    return false;
  }
  public static synchronized boolean clear() {
    load();
    List<BaseFingerprint> previous = new ArrayList<>(PATTERNS);
    PATTERNS.clear();
    if (save()) return true;
    PATTERNS.addAll(previous);
    return false;
  }
}
