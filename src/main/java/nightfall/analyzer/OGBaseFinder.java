package nightfall.analyzer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import nightfall.core.Modules;
import nightfall.gui.NoticeManager;

/** Zentrale Steuerung: inkrementeller Scan mit festen Speicher- und Tick-Budgets. */
public final class OGBaseFinder {
  public static final Map<Long, Result> results = new ConcurrentHashMap<>();
  static final Map<Long, Integer> seen = new HashMap<>();
  static final Set<Long> notified = new HashSet<>();
  static String dim = "";
  static int offR = -1;
  static List<int[]> offs = new ArrayList<>();
  static double lastX = Double.NaN, lastZ = Double.NaN;

  public static boolean shown(Result r) {
    if (r == null || r.learnedFake()) return false;
    var m = Modules.BASE;
    if (r.decoy()) return m.on("Show Decoys");
    return r.score() >= Math.max(50, m.s("Min Score").i())
        && r.evidence() >= Math.max(2, m.s("Min Evidence").i());
  }

  public static int color(Result r) {
    if (r.decoy()) return 0xFF6B6B7B;
    return r.score() < 50 ? 0xFFEAB308 : r.score() < 75 ? 0xFFF97316 : 0xFFEF4444;
  }

  public static void clearForTeleport() {
    results.clear();
    seen.clear();
    notified.clear();
  }

  /** Speichert das aktuelle Chunk-Profil als vom Spieler bestaetigtes Fake-Base-Muster. */
  public static String learnFake(Minecraft mc, int cx, int cz) {
    if (mc == null || mc.level == null || mc.player == null) return "Keine aktive Welt.";
    long key = ChunkPos.asLong(cx, cz);
    Result result = results.get(key);
    if (result == null) {
      LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
      if (chunk == null) return "Chunk ist clientseitig nicht geladen; stelle dich naeher heran und versuche es erneut.";
      result = ChunkAnalyzer.analyze(mc.level, chunk, mc.player.tickCount);
    }
    if (result.fingerprint() == null || !result.fingerprint().canLearn())
      return "Zu wenige unterschiedliche Merkmale in diesem Chunk; kein Muster gespeichert.";
    if (FakeBaseMemory.size() >= 32) return "Speicher voll (32 Muster). Entferne zuerst ein Muster mit /nfsus remove <nummer>.";
    if (!FakeBaseMemory.learn(result.fingerprint()))
      return "Muster wurde nicht hinzugefuegt (bereits aehnlich vorhanden oder Datei nicht beschreibbar).";
    results.put(key, result.asLearnedFake(mc.player.tickCount));
    notified.remove(key);
    trimResults();
    return "Fake-Base-Muster gespeichert (#" + FakeBaseMemory.size() + "). Dieser Chunk und sehr aehnliche Muster werden ausgeblendet.";
  }

  private static void trimResults() {
    var m = Modules.BASE;
    int maxSus = Math.max(8, Math.min(256, m.s("Max Sus Chunks").i()));
    int maxStored = Math.max(maxSus, Math.max(64, Math.min(1024, m.s("Max Stored Chunks").i())));

    List<Map.Entry<Long, Result>> suspects = new ArrayList<>();
    for (var entry : results.entrySet()) if (shown(entry.getValue())) suspects.add(entry);
    suspects.sort(Comparator.<Map.Entry<Long, Result>>comparingInt(e -> e.getValue().score()).reversed()
        .thenComparing(Comparator.comparingInt((Map.Entry<Long, Result> e) -> e.getValue().tick()).reversed()));
    for (int i = maxSus; i < suspects.size(); i++) results.remove(suspects.get(i).getKey(), suspects.get(i).getValue());

    while (results.size() > maxStored) {
      Map.Entry<Long, Result> victim = results.entrySet().stream().min(
          Comparator.<Map.Entry<Long, Result>>comparingInt(e -> shown(e.getValue()) ? 1 : 0)
              .thenComparingInt(e -> e.getValue().score())
              .thenComparingInt(e -> e.getValue().tick())).orElse(null);
      if (victim == null) break;
      results.remove(victim.getKey(), victim.getValue());
      notified.remove(victim.getKey());
    }
    notified.removeIf(key -> !results.containsKey(key));
  }

  public static void tick(Minecraft mc) {
    var m = Modules.BASE;
    if (mc.level == null || mc.player == null) { lastX = lastZ = Double.NaN; return; }
    String d = mc.level.dimension().toString();
    if (!d.equals(dim)) { dim = d; clearForTeleport(); lastX = lastZ = Double.NaN; }
    double px = mc.player.getX(), pz = mc.player.getZ();
    if (!Double.isNaN(lastX) && m.on("Auto Clear RTP")) {
      double threshold = m.s("RTP Threshold").d();
      if ((px - lastX) * (px - lastX) + (pz - lastZ) * (pz - lastZ) > threshold * threshold) {
        clearForTeleport();
        NoticeManager.push("Gebiet gewechselt", "Alte Chunk-Markierungen entfernt", 0xFFA855F7);
      }
    }
    lastX = px; lastZ = pz;
    if (!m.enabled) return;

    int range = m.s("Range").i();
    int budget = Math.max(1, Math.min(4, m.s("Scan Speed").i()));
    int tick = mc.player.tickCount;
    if (range != offR) {
      offR = range;
      offs = new ArrayList<>();
      for (int x = -range; x <= range; x++) for (int z = -range; z <= range; z++) offs.add(new int[] {x, z});
      offs.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }

    int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
    int nearRecheck = Math.max(20, m.s("Near Recheck Ticks").i());
    for (int[] offset : offs) {
      if (budget <= 0) break;
      int cx = pcx + offset[0], cz = pcz + offset[1];
      long key = ChunkPos.asLong(cx, cz);
      Result old = results.get(key);
      int chunkDistance = Math.max(Math.abs(offset[0]), Math.abs(offset[1]));
      boolean refreshNearbySuspect = old != null && shown(old) && chunkDistance <= 1
          && tick - old.tick() >= nearRecheck;
      if (old != null && tick - old.tick() < 6000 && !refreshNearbySuspect) continue;

      var chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
      if (chunk == null) continue;
      int firstSeen = seen.computeIfAbsent(key, ignored -> tick);
      if (tick - firstSeen < 40) continue; // Chunkdaten/Block-Entities kurz stabilisieren lassen.

      Result result = ChunkAnalyzer.analyze(mc.level, chunk, tick);
      results.put(key, result);
      budget--;
      trimResults();
      if (results.get(key) == result && m.on("Notify") && !result.decoy() && shown(result)) {
        boolean near = false;
        for (long notifiedKey : notified) {
          if (Math.max(Math.abs(ChunkPos.getX(notifiedKey) - cx), Math.abs(ChunkPos.getZ(notifiedKey) - cz)) <= 3) { near = true; break; }
        }
        if (!near) {
          notified.add(key);
          mc.player.displayClientMessage(Component.literal("[Nightfall] Auffaelliger Chunk bei " + (cx * 16) + " " + (cz * 16)
              + " | Score " + result.score() + " | Konfidenz " + result.conf() + "%"), false);
          NoticeManager.push("Sus-Chunk gefunden", (cx * 16) + ", " + (cz * 16) + " | Score " + result.score(), color(result));
        }
      }
    }
  }
}
