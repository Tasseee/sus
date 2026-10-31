package nightfall.analyzer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import nightfall.core.Modules;
/** Bewertet einen geladenen Chunk anhand sichtbarer Daten (Block-Entities und Bloecke). */
public final class ChunkAnalyzer {
  static Set<Block> set(Block... b) { return new HashSet<>(Arrays.asList(b)); }
  static final Set<Block> LIGHT = set(Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.END_ROD, Blocks.SEA_LANTERN);
  static final Set<Block> RS = set(Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON,
    Blocks.REPEATER, Blocks.COMPARATOR, Blocks.REDSTONE_LAMP, Blocks.REDSTONE_BLOCK, Blocks.TNT, Blocks.LEVER);
  static final Set<Block> ART = set(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.STONE_BRICKS, Blocks.BRICKS, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE,
    Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.IRON_BLOCK,
    Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
    Blocks.IRON_DOOR, Blocks.WHITE_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.SCAFFOLDING);
  static final Set<Block> SPECIAL = set(Blocks.COBWEB, Blocks.MOSSY_COBBLESTONE, Blocks.BELL, Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE,
    Blocks.SCULK_SHRIEKER, Blocks.NETHERITE_BLOCK, Blocks.BEACON);
  static boolean interesting(BlockState s) {
    Block b = s.getBlock();
    return LIGHT.contains(b) || RS.contains(b) || ART.contains(b) || SPECIAL.contains(b) || s.is(BlockTags.RAILS);
  }

  public static Result analyze(Level lv, LevelChunk ch, int tick) {
    var m = Modules.BASE; boolean nat = m.on("Natural Filter");
    int storage = 0, shulk = 0, spawn = 0, decoy = 0, trial = 0; double[] an = null;
    for (BlockEntity be : ch.getBlockEntities().values()) {
      var t = be.getType(); BlockPos p = be.getBlockPos();
      if (t == BlockEntityType.MOB_SPAWNER) {
        if (FakeSpawnerFilter.isKnownFakeSpawner(lv, p)) decoy++;
        else { spawn++; if (an == null) an = new double[] { p.getX() + .5, p.getY() + .5, p.getZ() + .5 }; }
      } else if (t == BlockEntityType.CHEST || t == BlockEntityType.TRAPPED_CHEST || t == BlockEntityType.BARREL || t == BlockEntityType.SHULKER_BOX || t == BlockEntityType.ENDER_CHEST) {
        storage++; if (t == BlockEntityType.SHULKER_BOX) shulk++;
        if (an == null) an = new double[] { p.getX() + .5, p.getY() + .5, p.getZ() + .5 };
      } else if (t == BlockEntityType.TRIAL_SPAWNER || t == BlockEntityType.VAULT) trial++;
    }
    int light = 0, rs = 0, art = 0, web = 0, rail = 0, moss = 0, bell = 0, frame = 0, reinf = 0, shriek = 0, valuable = 0;
    LevelChunkSection[] secs = ch.getSections();
    for (LevelChunkSection sec : secs) {
      if (sec.hasOnlyAir() || !sec.maybeHas(ChunkAnalyzer::interesting)) continue;
      for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
        BlockState s = sec.getBlockState(x, y, z); if (s.isAir()) continue;
        Block b = s.getBlock();
        if (LIGHT.contains(b)) light++; else if (RS.contains(b)) rs++; else if (ART.contains(b)) art++;
        else if (b == Blocks.COBWEB) web++; else if (b == Blocks.MOSSY_COBBLESTONE) moss++; else if (b == Blocks.BELL) bell++;
        else if (b == Blocks.END_PORTAL_FRAME) frame++; else if (b == Blocks.REINFORCED_DEEPSLATE) reinf++; else if (b == Blocks.SCULK_SHRIEKER) shriek++;
        else if (b == Blocks.NETHERITE_BLOCK || b == Blocks.BEACON) valuable++;
        else if (s.is(BlockTags.RAILS)) rail++;
      }
    }
    int score = 0, ev = 0, naturals = 0; List<String> why = new ArrayList<>();

    // Nur mehrere voneinander unabhaengige, starke Hinweise duerfen einen Chunk
    // als verdaechtig einstufen. Einzelne Truhen, Fackeln, Werkbaenke oder
    // normale Redstone-Bloecke kommen auch in ganz normalen Chunks vor.
    boolean storageSignal = storage >= 6;
    boolean redstoneSignal = rs >= 12;
    boolean valuableSignal = valuable >= 1;

    if (storageSignal) {
      int p = storage >= 24 ? 36 : storage >= 12 ? 30 : 24;
      score += p; ev++;
      why.add("Groessere Lagerstruktur: " + storage + " (+" + p + ")");
      if (shulk >= 3) { score += 8; why.add("Mehrere Shulker-Boxen: " + shulk + " (+8 Zusatzhinweis)"); }
    } else if (storage > 0) {
      why.add("Einzelne Lagerbloecke (kein Verdachtshinweis): " + storage);
    }

    if (redstoneSignal) {
      int p = rs >= 64 ? 36 : rs >= 32 ? 32 : 24;
      score += p; ev++;
      why.add("Groessere Redstone-/Maschinenstruktur: " + rs + " (+" + p + ")");
    } else if (rs > 0) {
      why.add("Wenig Redstone (kein Verdachtshinweis): " + rs);
    }

    if (valuableSignal) {
      score += 35; ev++;
      why.add("Seltene Wertbloecke (Netherite/Beacon): " + valuable + " (+35)");
    }

    // Bau-/Lichtbloecke sind nur Kontext. Sie zaehlen niemals als eigener Beweis.
    if (art >= 160 && (storageSignal || redstoneSignal)) {
      score += 5;
      why.add("Groessere kuenstliche Bauumgebung: " + art + " (+5 Zusatzhinweis)");
    }
    if (light >= 6) why.add("Beleuchtung erkannt (wird nicht allein gewertet): " + light);

    // Ein Spawner allein ist in Vanilla normal (z.B. Dungeon) und erzeugt
    // deshalb keinen Verdachtspunkt. Er wird nur als Kontext dokumentiert.
    if (spawn > 0) why.add("Spawner erkannt (allein kein Verdachtshinweis): " + spawn);

    boolean dungeon = spawn > 0 && moss > 0;
    if (nat) {
      if (web > 0 && rail > 0) { score -= 35; naturals++; why.add("Natuerliche Struktur: Mineshaft (-35)"); }
      if (dungeon) { score -= 30; naturals++; why.add("Natuerliche Struktur: Dungeon (-30)"); }
      if (bell > 0) { score -= 40; naturals++; why.add("Natuerliche Struktur: Dorf (-40)"); }
      if (trial > 0) { score -= 40; naturals++; why.add("Natuerliche Struktur: Trial Chamber (-40)"); }
      if (reinf > 0 || shriek > 0) { score -= 40; naturals++; why.add("Natuerliche Struktur: Ancient City (-40)"); }
      if (frame > 0) { score -= 40; naturals++; why.add("Natuerliche Struktur: Stronghold (-40)"); }
    }
    BaseFingerprint fingerprint = new BaseFingerprint(storage, shulk, rs, art, light, spawn,
        web, rail, moss, bell + frame + reinf + shriek, valuable);
    int learnedMatch = FakeBaseMemory.matchPercent(fingerprint);
    boolean learnedFake = learnedMatch >= 88;
    if (learnedFake) {
      score = 0;
      ev = 0;
      why.add("Gelerntes Fake-Base-Muster erkannt (" + learnedMatch + "% Aehnlichkeit)");
    }
    score = Math.max(0, Math.min(100, score));
    if (decoy > 0) why.add("Fake-Spawner an Bedrock-Chunk-Ecke ignoriert: " + decoy);
    boolean isDecoy = learnedFake || (decoy > 0 && score < 25);
    int conf = Math.max(5, Math.min(85, 15 + 25 * ev - (naturals > 0 ? 20 : 0)));
    if (an == null) { int cx = ch.getPos().x * 16 + 8, cz = ch.getPos().z * 16 + 8; an = new double[] { cx, lv.getMinY() + 32, cz }; }
    return new Result(ch.getPos().x, ch.getPos().z, score, conf, ev, 100, isDecoy, learnedFake,
        why, an[0], an[1], an[2], tick, fingerprint);
  }
}
