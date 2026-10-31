package nightfall.visuals;
import java.util.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import nightfall.core.*;
import nightfall.analyzer.FakeSpawnerFilter;
import nightfall.gui.Hud;
import nightfall.gui.NoticeManager;
import nightfall.core.Module;
/** 2D-Projektions-ESP: rechnet Weltpunkte selbst auf den Bildschirm um (kein Eingriff in die 3D-Render-Pipeline). */
public final class Esp {
  record Hit(AABB box, int type) {}
  record BlockEspHit(BlockPos pos, int type) {}
  record BlockTarget(String setting, Block block) {}
  static volatile List<Hit> storage = List.of();
  static volatile List<Hit> spawners = List.of();
  static volatile List<BlockEspHit> blockEsp = List.of();
  static final BlockTarget[] BLOCK_TARGETS = {
    new BlockTarget("Chest", Blocks.CHEST),
    new BlockTarget("Trapped Chest", Blocks.TRAPPED_CHEST),
    new BlockTarget("Barrel", Blocks.BARREL),
    new BlockTarget("Shulker Box", Blocks.SHULKER_BOX),
    new BlockTarget("Ender Chest", Blocks.ENDER_CHEST),
    new BlockTarget("Spawner", Blocks.SPAWNER),
    new BlockTarget("Diamond Ore", Blocks.DIAMOND_ORE),
    new BlockTarget("Diamond Ore", Blocks.DEEPSLATE_DIAMOND_ORE),
    new BlockTarget("Ancient Debris", Blocks.ANCIENT_DEBRIS),
    new BlockTarget("Emerald Ore", Blocks.EMERALD_ORE),
    new BlockTarget("Emerald Ore", Blocks.DEEPSLATE_EMERALD_ORE),
    new BlockTarget("Gold Ore", Blocks.GOLD_ORE),
    new BlockTarget("Gold Ore", Blocks.DEEPSLATE_GOLD_ORE),
    new BlockTarget("Iron Ore", Blocks.IRON_ORE),
    new BlockTarget("Iron Ore", Blocks.DEEPSLATE_IRON_ORE),
    new BlockTarget("Redstone Ore", Blocks.REDSTONE_ORE),
    new BlockTarget("Redstone Ore", Blocks.DEEPSLATE_REDSTONE_ORE),
    new BlockTarget("Lapis Ore", Blocks.LAPIS_ORE),
    new BlockTarget("Lapis Ore", Blocks.DEEPSLATE_LAPIS_ORE),
    new BlockTarget("Copper Ore", Blocks.COPPER_ORE),
    new BlockTarget("Copper Ore", Blocks.DEEPSLATE_COPPER_ORE),
    new BlockTarget("Obsidian", Blocks.OBSIDIAN),
    new BlockTarget("Crying Obsidian", Blocks.CRYING_OBSIDIAN),
    new BlockTarget("Beacon", Blocks.BEACON),
    new BlockTarget("Hopper", Blocks.HOPPER),
    new BlockTarget("Furnace", Blocks.FURNACE),
    new BlockTarget("Blast Furnace", Blocks.BLAST_FURNACE),
    new BlockTarget("Smoker", Blocks.SMOKER),
    new BlockTarget("Dispenser", Blocks.DISPENSER),
    new BlockTarget("Dropper", Blocks.DROPPER),
    new BlockTarget("Piston", Blocks.PISTON),
    new BlockTarget("Sticky Piston", Blocks.STICKY_PISTON),
    new BlockTarget("Observer", Blocks.OBSERVER),
    new BlockTarget("End Portal Frame", Blocks.END_PORTAL_FRAME),
    new BlockTarget("Respawn Anchor", Blocks.RESPAWN_ANCHOR),
    new BlockTarget("Lodestone", Blocks.LODESTONE)
  };
  static final Map<Block, Integer> BLOCK_TARGET_INDEX = createBlockTargetIndex();
  static final EntityType<?>[] ET = { EntityType.VILLAGER, EntityType.WANDERING_TRADER, EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME, EntityType.ARMOR_STAND, EntityType.MINECART, EntityType.ALLAY };
  static final String[] EN = { "Villager", "Wandering Trader", "Item Frame", "Glow Item Frame", "Armor Stand", "Minecart", "Allay" };
  static final String[] SN = { "Chest", "Trapped Chest", "Barrel", "Shulker Box", "Ender Chest" };
  static final int[] SC = { 0xFFE8A33D, 0xFFE5484D, 0xFF9C6B3C, 0xFFE879F9, 0xFF22D3EE };
  static double cx, cy, cz, fx, fy, fz, rx, rz, ux, uy, uz, th, asp; static int W, H, tracers;
  private static final double NEAR_CLIP = 0.06;
  static final Set<java.util.UUID> notifiedItems = new HashSet<>();
  static final Set<String> notifiedBlocks = new HashSet<>();
  static final Set<java.util.UUID> notifiedEntities = new HashSet<>();

  private static Map<Block, Integer> createBlockTargetIndex() {
    Map<Block, Integer> out = new HashMap<>();
    for (int i = 0; i < BLOCK_TARGETS.length; i++) out.put(BLOCK_TARGETS[i].block(), i);
    return Collections.unmodifiableMap(out);
  }

  private static void scanBlockEsp(Minecraft mc) {
    var module = Modules.BLOCK_ESP;
    if (module == null || !module.enabled || mc.level == null || mc.player == null) {
      blockEsp = List.of();
      return;
    }
    int range = module.s("Range").i();
    int vertical = module.s("Vertical Range").i();
    int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
    int minX = px - range, maxX = px + range, minZ = pz - range, maxZ = pz + range;
    int minY = Math.max(mc.level.getMinY(), py - vertical);
    int maxY = Math.min(mc.level.getMaxY() - 1, py + vertical);
    int minChunkX = Math.floorDiv(minX, 16), maxChunkX = Math.floorDiv(maxX, 16);
    int minChunkZ = Math.floorDiv(minZ, 16), maxChunkZ = Math.floorDiv(maxZ, 16);
    List<BlockEspHit> found = new ArrayList<>();
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    scan:
    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        var chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) continue;
        int fromX = Math.max(minX, chunkX << 4), toX = Math.min(maxX, (chunkX << 4) + 15);
        int fromZ = Math.max(minZ, chunkZ << 4), toZ = Math.min(maxZ, (chunkZ << 4) + 15);
        for (int x = fromX; x <= toX; x++) for (int z = fromZ; z <= toZ; z++) {
          int dx = x - px, dz = z - pz;
          if (dx * dx + dz * dz > range * range) continue;
          for (int y = minY; y <= maxY; y++) {
            Integer index = BLOCK_TARGET_INDEX.get(chunk.getBlockState(pos.set(x, y, z)).getBlock());
            if (index == null) continue;
            String setting = BLOCK_TARGETS[index].setting();
            if (!module.on(setting)) continue;
            found.add(new BlockEspHit(new BlockPos(x, y, z), index));
            if (found.size() >= 1800) break scan;
          }
        }
      }
    }
    blockEsp = List.copyOf(found);
  }

  public static void tick(Minecraft mc) {
    boolean scanStorage = Modules.STORAGE.enabled;
    boolean scanSpawners = Modules.SPAWNER.enabled;
    boolean scanItems = Modules.ITEM != null && Modules.ITEM.enabled && Modules.ITEM.on("Notify");
    if (mc.level == null || mc.player == null) { storage = List.of(); spawners = List.of(); blockEsp = List.of(); notifiedItems.clear(); notifiedBlocks.clear(); notifiedEntities.clear(); return; }
    int blockScanInterval = Modules.BLOCK_ESP != null && Modules.BLOCK_ESP.s("Range").i() > 64 ? 30 : 15;
    if (mc.player.tickCount % blockScanInterval == 0) scanBlockEsp(mc);
    if (!Modules.BLOCK_ESP.enabled) blockEsp = List.of();
    if (mc.player.tickCount % 20 != 0) return;
    for (Entity e : mc.level.entitiesForRendering()) {
      double d2 = e.distanceToSqr(mc.player);
      if (scanItems && e instanceof ItemEntity item && d2 <= Modules.ITEM.s("Range").d() * Modules.ITEM.s("Range").d() && notifiedItems.add(item.getUUID())) {
        String itemName = item.getItem().getHoverName().getString();
        NoticeManager.push("Item entdeckt", itemName, Modules.ITEM.s("Color").rgb());
      }
      if (Modules.PESP.enabled && Modules.PESP.on("Notify") && e.getType() == EntityType.PLAYER && d2 <= Modules.PESP.s("Range").d() * Modules.PESP.s("Range").d() && notifiedEntities.add(e.getUUID()))
        NoticeManager.push("Spieler entdeckt", e.getName().getString(), Modules.PESP.s("Color").rgb());
      if (Modules.EESP.enabled && Modules.EESP.on("Notify") && d2 <= Modules.EESP.s("Range").d() * Modules.EESP.s("Range").d() && notifiedEntities.add(e.getUUID())) {
        for (int i = 0; i < ET.length; i++) if (e.getType() == ET[i] && Modules.EESP.on(EN[i])) { NoticeManager.push("Entity entdeckt", EN[i], Modules.EESP.s("Color").rgb()); break; }
      }
    }
    if (!scanStorage && !scanSpawners) return;
    int range = Math.max(scanStorage ? Modules.STORAGE.s("Range").i() : 0, scanSpawners ? Modules.SPAWNER.s("Range").i() : 0);
    int r = (int) Math.ceil(range / 16.0), pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
    List<Hit> out = new ArrayList<>(), spawnerOut = new ArrayList<>();
    for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
      var ch = mc.level.getChunkSource().getChunkNow(pcx + a, pcz + b); if (ch == null) continue;
      for (var be : ch.getBlockEntities().values()) {
        var t = be.getType();
        if (scanStorage) {
          int k = t == BlockEntityType.CHEST ? 0 : t == BlockEntityType.TRAPPED_CHEST ? 1 : t == BlockEntityType.BARREL ? 2 : t == BlockEntityType.SHULKER_BOX ? 3 : t == BlockEntityType.ENDER_CHEST ? 4 : -1;
          if (k >= 0 && Modules.STORAGE.on(SN[k])) out.add(new Hit(new AABB(be.getBlockPos()), k));
        }
        if (scanSpawners && t == BlockEntityType.MOB_SPAWNER && !FakeSpawnerFilter.isKnownFakeSpawner(mc.level, be.getBlockPos()))
          spawnerOut.add(new Hit(new AABB(be.getBlockPos()), 0));
      }
    }
    storage = out;
    spawners = spawnerOut;
    if (scanStorage && Modules.STORAGE.on("Notify")) for (Hit h : out) {
      String id = "s:" + h.box.getCenter().x + ":" + h.box.getCenter().y + ":" + h.box.getCenter().z;
      if (notifiedBlocks.add(id)) NoticeManager.push("Block entdeckt", SN[h.type], SC[h.type]);
    }
    if (scanSpawners && Modules.SPAWNER.on("Notify")) for (Hit h : spawnerOut) {
      String id = "p:" + h.box.getCenter().x + ":" + h.box.getCenter().y + ":" + h.box.getCenter().z;
      if (notifiedBlocks.add(id)) NoticeManager.push("Spawner entdeckt", "Geladener Chunk", Modules.SPAWNER.s("Color").rgb());
    }
    if (notifiedBlocks.size() > 4096) notifiedBlocks.clear();
    if (notifiedItems.size() > 2048) notifiedItems.clear();
  }

  static void setup(Minecraft mc, float pt) {
    var p = mc.player;
    if (Cam.freecam) { cx = Cam.x; cy = Cam.y; cz = Cam.z; } else { var v = p.getEyePosition(pt); cx = v.x; cy = v.y; cz = v.z; }
    float yaw = Cam.active() ? Cam.yaw : p.getViewYRot(pt), pitch = Cam.active() ? Cam.pitch : p.getViewXRot(pt);
    double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
    fx = -Math.sin(yr) * Math.cos(pr); fy = -Math.sin(pr); fz = Math.cos(yr) * Math.cos(pr);
    rx = -Math.cos(yr); rz = -Math.sin(yr);
    ux = -rz * fy; uy = rz * fx - rx * fz; uz = rx * fy;
    th = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
    W = mc.getWindow().getGuiScaledWidth(); H = mc.getWindow().getGuiScaledHeight(); asp = (double) W / H; tracers = 0;
  }
  static double[] proj(double x, double y, double z) {
    double dx = x - cx, dy = y - cy, dz = z - cz, zc = dx * fx + dy * fy + dz * fz;
    if (zc < NEAR_CLIP) return null;
    double xc = dx * rx + dz * rz, yc = dx * ux + dy * uy + dz * uz;
    return new double[] { W / 2.0 + xc / (zc * th * asp) * W / 2.0, H / 2.0 - yc / (zc * th) * H / 2.0 };
  }
  static double[] box2d(AABB b) {
    double x1 = Double.POSITIVE_INFINITY, y1 = Double.POSITIVE_INFINITY;
    double x2 = Double.NEGATIVE_INFINITY, y2 = Double.NEGATIVE_INFINITY;
    double[][] v = new double[8][3];
    double[] d = new double[8];
    boolean any = false;
    for (int i = 0; i < 8; i++) {
      v[i][0] = (i & 1) == 0 ? b.minX : b.maxX;
      v[i][1] = (i & 2) == 0 ? b.minY : b.maxY;
      v[i][2] = (i & 4) == 0 ? b.minZ : b.maxZ;
      d[i] = depth(v[i][0], v[i][1], v[i][2]);
      if (d[i] >= NEAR_CLIP) {
        double[] q = proj(v[i][0], v[i][1], v[i][2]);
        if (q != null) { x1 = Math.min(x1, q[0]); y1 = Math.min(y1, q[1]); x2 = Math.max(x2, q[0]); y2 = Math.max(y2, q[1]); any = true; }
      }
    }
    for (int i = 0; i < 8; i++) for (int bit = 0; bit < 3; bit++) {
      if ((i & (1 << bit)) != 0) continue;
      int j = i | (1 << bit);
      if ((d[i] < NEAR_CLIP) == (d[j] < NEAR_CLIP)) continue;
      double t = (0.05 - d[i]) / (d[j] - d[i]);
      double[] q = proj(v[i][0] + (v[j][0] - v[i][0]) * t,
          v[i][1] + (v[j][1] - v[i][1]) * t,
          v[i][2] + (v[j][2] - v[i][2]) * t);
      if (q != null) { x1 = Math.min(x1, q[0]); y1 = Math.min(y1, q[1]); x2 = Math.max(x2, q[0]); y2 = Math.max(y2, q[1]); any = true; }
    }
    return any ? new double[] { x1, y1, x2, y2 } : null;
  }
  /** One shared antialiased line path for ESP boxes, tracers and overlays. */
  static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int c) {
    smoothLine(g, x1, y1, x2, y2, c);
  }

  private static double depth(double x, double y, double z) {
    return (x - cx) * fx + (y - cy) * fy + (z - cz) * fz;
  }

  /** Draws the twelve projected edges of a world-space AABB as clean straight lines.
   * Edges crossing the near plane are clipped instead of being discarded. */
  private static void drawWireBox(GuiGraphics g, AABB b, int color) {
    double[][] v = new double[8][3];
    for (int i = 0; i < 8; i++) {
      v[i][0] = (i & 1) == 0 ? b.minX : b.maxX;
      v[i][1] = (i & 2) == 0 ? b.minY : b.maxY;
      v[i][2] = (i & 4) == 0 ? b.minZ : b.maxZ;
    }
    for (int i = 0; i < 8; i++) {
      for (int bit = 0; bit < 3; bit++) {
        if ((i & (1 << bit)) != 0) continue;
        double[] a = v[i], b1 = v[i | (1 << bit)];
        double da = depth(a[0], a[1], a[2]);
        double db = depth(b1[0], b1[1], b1[2]);
        if (da < NEAR_CLIP && db < NEAR_CLIP) continue;
        double ax = a[0], ay = a[1], az = a[2];
        double bx = b1[0], by = b1[1], bz = b1[2];
        if (da < NEAR_CLIP || db < NEAR_CLIP) {
          double t = (NEAR_CLIP - da) / (db - da);
          double ix = ax + (bx - ax) * t;
          double iy = ay + (by - ay) * t;
          double iz = az + (bz - az) * t;
          if (da < 0.05) { ax = ix; ay = iy; az = iz; }
          else { bx = ix; by = iy; bz = iz; }
        }
        double[] p1 = proj(ax, ay, az), p2 = proj(bx, by, bz);
        if (p1 != null && p2 != null) smoothLine(g, p1[0], p1[1], p2[0], p2[1], color);
      }
    }
  }
  static void draw(GuiGraphics g, Font f, AABB b, int col, boolean box, String label, boolean tracer, double hp) {
    double[] r = box2d(b); if (r == null) { if (tracer) offscreen(g, b, col, true); return; }
    int x1 = (int) r[0], y1 = (int) r[1], x2 = (int) r[2], y2 = (int) r[3];
    if (x2 < 0 || x1 > W || y2 < 0 || y1 > H) { if (tracer) offscreen(g, b, col, true); return; }
    if (box) drawWireBox(g, b, col);
    if (hp >= 0) { int h = (int) ((y2 - y1) * hp); g.fill(x1 - 4, y1, x1 - 2, y2, 0xAA000000); g.fill(x1 - 4, y2 - h, x1 - 2, y2, Hud.lerp(0xFFEF4444, 0xFF22C55E, (float) hp)); }
    if (label != null && !label.isEmpty()) {
      int w = f.width(label), lx = (x1 + x2) / 2 - w / 2; g.fill(lx - 2, y1 - 12, lx + w + 2, y1 - 1, 0xA0000000); g.drawString(f, label, lx, y1 - 10, 0xFFFFFFFF, false);
    }
    if (tracer && tracers++ < 40) line(g, W / 2.0, H / 2.0, (x1 + x2) / 2.0, (y1 + y2) / 2.0, (col & 0x00FFFFFF) | 0xA0000000);
  }

  static void offscreen(GuiGraphics g, AABB b, int col, boolean tracer) {
    if (!tracer) return;
    double dx = b.getCenter().x - cx, dy = b.getCenter().y - cy, dz = b.getCenter().z - cz;
    double zc = dx * fx + dy * fy + dz * fz;
    double xc = dx * rx + dz * rz, yc = dx * ux + dy * uy + dz * uz;
    if (zc < 0) { xc = -xc; yc = -yc; }
    double sx = xc / (Math.max(1.0, Math.abs(zc)) * th * asp) * W / 2.0;
    double sy = -yc / (Math.max(1.0, Math.abs(zc)) * th) * H / 2.0;
    double len = Math.max(1.0, Math.hypot(sx, sy));
    double vx = sx / len, vy = sy / len;
    double edge = Math.min((W / 2.0 - 18) / Math.max(0.001, Math.abs(vx)), (H / 2.0 - 18) / Math.max(0.001, Math.abs(vy)));
    double ex = W / 2.0 + vx * edge, ey = H / 2.0 + vy * edge;
    line(g, W / 2.0, H / 2.0, ex, ey, (col & 0x00FFFFFF) | 0x65000000);
    double px = -vy, py = vx;
    line(g, ex, ey, ex - vx * 8 + px * 5, ey - vy * 8 + py * 5, col);
    line(g, ex, ey, ex - vx * 8 - px * 5, ey - vy * 8 - py * 5, col);
    line(g, ex - vx * 8 + px * 5, ey - vy * 8 + py * 5, ex - vx * 8 - px * 5, ey - vy * 8 - py * 5, col);
  }

  /** Draw one sub-pixel antialiased sample. GuiGraphics uses integer GUI pixels, so
   * coverage is represented through alpha instead of a staircase of opaque pixels. */
  private static void aaPixel(GuiGraphics g, int x, int y, int color, double coverage) {
    if (coverage <= 0.015 || x < 0 || y < 0 || x >= W || y >= H) return;
    int alpha = (int) Math.round(((color >>> 24) & 0xFF) * Math.min(1.0, coverage));
    if (alpha <= 0) return;
    g.fill(x, y, x + 1, y + 1, (alpha << 24) | (color & 0x00FFFFFF));
  }

  private static double frac(double value) { return value - Math.floor(value); }
  private static double revFrac(double value) { return 1.0 - frac(value); }

  /** Xiaolin-Wu style line rasterizer for a clean, thin block-selection outline. */
  private static void smoothLine(GuiGraphics g, double x1, double y1, double x2, double y2, int color) {
    if (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)) return;
    // Clip first so very close ESP targets cannot produce huge rasterization loops.
    double dx0 = x2 - x1, dy0 = y2 - y1;
    double enter = 0.0, leave = 1.0;
    double[] clipP = { -dx0, dx0, -dy0, dy0 };
    double[] clipQ = { x1, W - 1.0 - x1, y1, H - 1.0 - y1 };
    for (int i = 0; i < 4; i++) {
      if (Math.abs(clipP[i]) < 1.0e-9) { if (clipQ[i] < 0.0) return; }
      else {
        double t = clipQ[i] / clipP[i];
        if (clipP[i] < 0.0) enter = Math.max(enter, t);
        else leave = Math.min(leave, t);
        if (enter > leave) return;
      }
    }
    double startX = x1, startY = y1;
    x1 = startX + enter * dx0; y1 = startY + enter * dy0;
    x2 = startX + leave * dx0; y2 = startY + leave * dy0;
    boolean steep = Math.abs(y2 - y1) > Math.abs(x2 - x1);
    if (steep) { double t = x1; x1 = y1; y1 = t; t = x2; x2 = y2; y2 = t; }
    if (x1 > x2) { double t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
    double dx = x2 - x1, dy = y2 - y1;
    if (Math.abs(dx) < 1.0e-6) return;
    double gradient = dy / dx;

    double xEnd = Math.floor(x1 + 0.5);
    double yEnd = y1 + gradient * (xEnd - x1);
    double xGap = revFrac(x1 + 0.5);
    int xStartPixel = (int) xEnd;
    int yStartPixel = (int) Math.floor(yEnd);
    if (steep) {
      aaPixel(g, yStartPixel, xStartPixel, color, revFrac(yEnd) * xGap);
      aaPixel(g, yStartPixel + 1, xStartPixel, color, frac(yEnd) * xGap);
    } else {
      aaPixel(g, xStartPixel, yStartPixel, color, revFrac(yEnd) * xGap);
      aaPixel(g, xStartPixel, yStartPixel + 1, color, frac(yEnd) * xGap);
    }
    double interY = yEnd + gradient;

    double xEnd2 = Math.floor(x2 + 0.5);
    double yEnd2 = y2 + gradient * (xEnd2 - x2);
    double xGap2 = frac(x2 + 0.5);
    int xEndPixel = (int) xEnd2;
    int yEndPixel = (int) Math.floor(yEnd2);

    for (int x = xStartPixel + 1; x < xEndPixel; x++) {
      int y = (int) Math.floor(interY);
      if (steep) {
        aaPixel(g, y, x, color, revFrac(interY));
        aaPixel(g, y + 1, x, color, frac(interY));
      } else {
        aaPixel(g, x, y, color, revFrac(interY));
        aaPixel(g, x, y + 1, color, frac(interY));
      }
      interY += gradient;
    }
    if (steep) {
      aaPixel(g, yEndPixel, xEndPixel, color, revFrac(yEnd2) * xGap2);
      aaPixel(g, yEndPixel + 1, xEndPixel, color, frac(yEnd2) * xGap2);
    } else {
      aaPixel(g, xEndPixel, yEndPixel, color, revFrac(yEnd2) * xGap2);
      aaPixel(g, xEndPixel, yEndPixel + 1, color, frac(yEnd2) * xGap2);
    }
  }

  static void drawBlockHighlight(GuiGraphics g, Minecraft mc) {
    if (Modules.BLOCK_HIGHLIGHT == null || !Modules.BLOCK_HIGHLIGHT.enabled || !Modules.BLOCK_HIGHLIGHT.on("Enabled")) return;
    if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
      return;
    }
    // A single, exact world-space wireframe. No screen-space interpolation, duplicate
    // edges or pixel-stepped line drawing: every edge is rasterized by smoothLine().
    drawWireBox(g, new AABB(hit.getBlockPos()), 0xE8FFFFFF);
  }

  private static void drawBlockEspMiniMap(GuiGraphics g, Minecraft mc, nightfall.core.Module module) {
    if (mc.player == null) return;
    int size = 116, margin = 12, x0 = W - size - margin, y0 = margin;
    int cx = x0 + size / 2, cy = y0 + size / 2;
    int range = Math.max(1, module.s("Range").i());
    g.fill(x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, 0xD90A0910);
    g.fill(x0, y0, x0 + size, y0 + size, 0xA91A1724);
    g.fill(x0, y0, x0 + size, y0 + 1, Hud.A);
    g.fill(x0, y0 + size - 1, x0 + size, y0 + size, 0x665F5870);
    g.fill(x0, y0, x0 + 1, y0 + size, 0x665F5870);
    g.fill(x0 + size - 1, y0, x0 + size, y0 + size, 0x665F5870);
    g.fill(cx, y0 + 5, cx + 1, y0 + size - 5, 0x553F3650);
    g.fill(x0 + 5, cy, x0 + size - 5, cy + 1, 0x553F3650);
    g.drawString(mc.font, "BLOCK ESP", x0 + 5, y0 + 5, 0xFFE4D9FF, false);
    double pX = mc.player.getX(), pZ = mc.player.getZ();
    for (BlockEspHit hit : blockEsp) {
      BlockTarget target = BLOCK_TARGETS[hit.type()];
      double dx = hit.pos().getX() + 0.5 - pX, dz = hit.pos().getZ() + 0.5 - pZ;
      if (Math.abs(dx) > range || Math.abs(dz) > range) continue;
      int px = cx + (int) Math.round(dx / range * (size / 2.0 - 7));
      int pz = cy + (int) Math.round(dz / range * (size / 2.0 - 7));
      int color = module.s("Color " + target.setting()).rgb();
      g.fill(px - 2, pz - 2, px + 3, pz + 3, color);
    }
    g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
  }

  public static void render(GuiGraphics g, DeltaTracker dt) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || mc.options.hideGui) return;
    nightfall.core.Module P = Modules.PESP, E = Modules.EESP, S = Modules.STORAGE;
    if (!P.enabled && !E.enabled && !S.enabled && !Modules.SPAWNER.enabled && !Modules.BASE.enabled && !Modules.ITEM.enabled && !Modules.BLOCK_HIGHLIGHT.enabled && !Modules.BLOCK_ESP.enabled) return;
    float pt = dt.getGameTimeDeltaPartialTick(false); setup(mc, pt); Font f = mc.font;
    for (Entity e : mc.level.entitiesForRendering()) {
      if (e == mc.player) continue;
      var off = e.getPosition(pt).subtract(e.position()); AABB bb = e.getBoundingBox().move(off);
      double dist = Math.sqrt(bb.getCenter().distanceToSqr(cx, cy, cz));
      if (Modules.ITEM.enabled && e instanceof ItemEntity item && dist <= Modules.ITEM.s("Range").d()) {
        String label = Modules.ITEM.on("Name") ? item.getItem().getHoverName().getString() + " x" + item.getItem().getCount() : "";
        draw(g, f, bb, Modules.ITEM.s("Color").rgb(), Modules.ITEM.on("Box"), label, Modules.ITEM.on("Tracer"), -1);
      } else if (P.enabled && e.getType() == EntityType.PLAYER && dist <= P.s("Range").d()) {
        String l = (P.on("Name") ? e.getName().getString() : "") + (P.on("Distance") ? String.format(" %.0fm", dist) : "");
        double hp = P.on("Health") && e instanceof LivingEntity le ? Math.max(0, Math.min(1, le.getHealth() / le.getMaxHealth())) : -1;
        draw(g, f, bb, P.s("Color").rgb(), P.on("Box"), l.trim(), P.on("Tracer"), hp);
      } else if (E.enabled && dist <= E.s("Range").d()) {
        for (int i = 0; i < ET.length; i++) if (e.getType() == ET[i] && E.on(EN[i]))
          draw(g, f, bb, E.s("Color").rgb(), E.on("Box"), E.on("Name") ? EN[i] : "", E.on("Tracer"), -1);
      }
    }
    if (S.enabled) for (Hit h : storage) {
      if (Math.sqrt(h.box.getCenter().distanceToSqr(cx, cy, cz)) > S.s("Range").d()) continue;
      draw(g, f, h.box, SC[h.type], S.on("Box"), "", S.on("Tracer"), -1);
    }
    var BE = Modules.BLOCK_ESP;
    if (BE.enabled) {
      for (BlockEspHit hit : blockEsp) {
        BlockTarget target = BLOCK_TARGETS[hit.type()];
        double dist = Math.sqrt(hit.pos().getCenter().distanceToSqr(cx, cy, cz));
        if (dist > BE.s("Range").d()) continue;
        int color = BE.s("Color " + target.setting()).rgb();
        draw(g, f, new AABB(hit.pos()), color, BE.on("Box"), BE.on("Name") ? target.setting() : "", BE.on("Tracer"), -1);
      }
      if (BE.on("Mini-map")) drawBlockEspMiniMap(g, mc, BE);
    }
    nightfall.core.Module SP = Modules.SPAWNER;
    if (SP.enabled) for (Hit h : spawners) {
      if (Math.sqrt(h.box.getCenter().distanceToSqr(cx, cy, cz)) > SP.s("Range").d()) continue;
      draw(g, f, h.box, SP.s("Color").rgb(), SP.on("Box"), "Spawner", SP.on("Tracer"), -1);
    }
    SusOverlay.draw(g, f, mc);
    drawBlockHighlight(g, mc);
  }
}
