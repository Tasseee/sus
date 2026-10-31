package nightfall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import nightfall.core.Modules;
import nightfall.gui.NoticeManager;

/** Server-dependent player watch. Staff names are explicitly user-maintained, never guessed. */
public final class PlayerWatch {
  private static final Set<String> STAFF_NAMES = new HashSet<>();
  private static final Set<UUID> STAFF_SEEN = new HashSet<>();
  private static final Set<UUID> WORLD_ONLY_SEEN = new HashSet<>();
  private static boolean loaded;
  private static int lastTick;
  private PlayerWatch() {}

  private static Path staffFile() {
    return FabricLoader.getInstance().getConfigDir().resolve("nightfall-staff.txt");
  }

  private static synchronized void loadNames() {
    if (loaded) return;
    loaded = true;
    Path file = staffFile();
    try {
      Files.createDirectories(file.getParent());
      if (!Files.exists(file)) Files.writeString(file,
          "# Nightfall Staff List\n# Add one exact Minecraft username per line, or use /nfstaff add <name>.\n",
          StandardCharsets.UTF_8);
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String name = line.strip();
        if (!name.isEmpty() && !name.startsWith("#")) STAFF_NAMES.add(name.toLowerCase(java.util.Locale.ROOT));
      }
    } catch (Exception e) {
      System.err.println("[Nightfall] staff list read failed: " + e);
    }
  }

  private static synchronized boolean saveNames() {
    Path file = staffFile();
    try {
      Files.createDirectories(file.getParent());
      List<String> lines = new ArrayList<>();
      lines.add("# Nightfall Staff List");
      lines.add("# One exact Minecraft username per line. Managed with /nfstaff add|remove|list.");
      STAFF_NAMES.stream().sorted().forEach(lines::add);
      Files.write(file, lines, StandardCharsets.UTF_8);
      return true;
    } catch (Exception e) {
      System.err.println("[Nightfall] staff list write failed: " + e);
      return false;
    }
  }

  public static synchronized boolean addStaffName(String rawName) {
    if (rawName == null) return false;
    String name = rawName.strip();
    if (!name.matches("[A-Za-z0-9_]{1,16}")) return false;
    loadNames();
    String key = name.toLowerCase(java.util.Locale.ROOT);
    if (!STAFF_NAMES.add(key)) return false;
    if (!saveNames()) { STAFF_NAMES.remove(key); return false; }
    return true;
  }

  public static synchronized boolean removeStaffName(String rawName) {
    if (rawName == null) return false;
    loadNames();
    String key = rawName.strip().toLowerCase(java.util.Locale.ROOT);
    if (!STAFF_NAMES.remove(key)) return false;
    if (!saveNames()) { STAFF_NAMES.add(key); return false; }
    return true;
  }

  public static synchronized void reloadStaffNames() {
    loaded = false;
    STAFF_NAMES.clear();
    loadNames();
    STAFF_SEEN.clear();
  }

  public static synchronized List<String> staffNames() {
    loadNames();
    return STAFF_NAMES.stream().sorted(Comparator.naturalOrder()).toList();
  }

  public static void tick(Minecraft mc) {
    if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.player.tickCount == lastTick) return;
    lastTick = mc.player.tickCount;
    if (mc.player.tickCount % 20 != 0) return;
    loadNames();
    Set<UUID> tab = new HashSet<>();
    for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
      var profile = info.getProfile();
      UUID id = profile.id();
      tab.add(id);
      String name = profile.name();
      if (Modules.STAFF.enabled && STAFF_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT)) && STAFF_SEEN.add(id)) {
        if (Modules.STAFF.on("Notify")) NoticeManager.push("Staff online", name + " (manuelle Liste)", 0xFFA855F7);
      }
    }
    if (Modules.ANTIVANISH.enabled && Modules.ANTIVANISH.on("Notify")) {
      for (Player p : mc.level.players()) {
        if (p == mc.player || p.distanceToSqr(mc.player) > 64 * 64) continue;
        if (!tab.contains(p.getUUID()) && WORLD_ONLY_SEEN.add(p.getUUID())) {
          NoticeManager.push("Tab/World-Abweichung", p.getName().getString() + " ist nah sichtbar, fehlt aber in Tab", 0xFFEAB308);
          mc.player.displayClientMessage(Component.literal("[Nightfall] Tab/World-Abweichung bei " + p.getName().getString() + " (kein sicherer Vanish-Nachweis)"), false);
        }
      }
    }
    STAFF_SEEN.retainAll(tab);
    WORLD_ONLY_SEEN.removeIf(id -> mc.level.players().stream().noneMatch(p -> p.getUUID().equals(id)));
  }

  public static List<String> knownStaffOnlineNames(Minecraft mc) {
    if (!Modules.STAFF.enabled || mc.getConnection() == null) return List.of();
    loadNames(); List<String> names = new ArrayList<>();
    for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
      String name = info.getProfile().name();
      if (STAFF_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) names.add(name);
    }
    return List.copyOf(names);
  }
}
