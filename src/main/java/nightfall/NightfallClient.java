package nightfall;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import nightfall.core.*;
import nightfall.core.Module;
import nightfall.gui.Hud;
import nightfall.gui.NoticeManager;
import nightfall.visuals.Esp;
import nightfall.analyzer.OGBaseFinder;
import org.lwjgl.glfw.GLFW;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
public class NightfallClient implements ClientModInitializer {
  public static boolean sound = true;
  private static final Map<Module, Boolean> down = new HashMap<>();
  @Override public void onInitializeClient() {
    Modules.init(); Config.load();
    FabricLoader.getInstance().getModContainer("nightfall").ifPresent(container ->
      ResourceManagerHelper.registerBuiltinResourcePack(Identifier.fromNamespaceAndPath("nightfall", "nightfall_cs_knife"), container,
        Component.literal("Nightfall CS Knife"), ResourcePackActivationType.NORMAL));
    HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath("nightfall", "hud"), (g, dt) -> { Esp.render(g, dt); Hud.render(g, dt); });
    ClientTickEvents.END_CLIENT_TICK.register(NightfallClient::tick);
    registerStaffCommands();
    registerSusCommands();
    Runtime.getRuntime().addShutdownHook(new Thread(Config::save));
  }

  private static void registerStaffCommands() {
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
      dispatcher.register(ClientCommandManager.literal("nfstaff")
        .executes(ctx -> {
          ctx.getSource().sendFeedback(Component.literal("Nightfall Staff List: /nfstaff add <name>, /nfstaff remove <name>, /nfstaff list, /nfstaff reload"));
          return 1;
        })
        .then(ClientCommandManager.literal("add")
          .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            boolean added = PlayerWatch.addStaffName(name);
            ctx.getSource().sendFeedback(Component.literal(added
              ? "[Nightfall] " + name + " zur lokalen Staff List hinzugefügt."
              : "[Nightfall] Konnte " + name + " nicht hinzufügen (ungültig, bereits vorhanden oder Datei nicht beschreibbar)."));
            return added ? 1 : 0;
          })))
        .then(ClientCommandManager.literal("remove")
          .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            boolean removed = PlayerWatch.removeStaffName(name);
            ctx.getSource().sendFeedback(Component.literal(removed
              ? "[Nightfall] " + name + " aus der lokalen Staff List entfernt."
              : "[Nightfall] " + name + " steht nicht in der Liste oder die Datei ist nicht beschreibbar."));
            return removed ? 1 : 0;
          })))
        .then(ClientCommandManager.literal("list").executes(ctx -> {
          var names = PlayerWatch.staffNames();
          ctx.getSource().sendFeedback(Component.literal("[Nightfall] Staff List (" + names.size() + "): "
            + (names.isEmpty() ? "leer" : String.join(", ", names))));
          return 1;
        }))
        .then(ClientCommandManager.literal("reload").executes(ctx -> {
          PlayerWatch.reloadStaffNames();
          ctx.getSource().sendFeedback(Component.literal("[Nightfall] Staff List neu geladen (" + PlayerWatch.staffNames().size() + " Namen)."));
          return 1;
        }))));
  }

  private static void registerSusCommands() {
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
      dispatcher.register(ClientCommandManager.literal("nfsus")
        .executes(ctx -> {
          ctx.getSource().sendFeedback(Component.literal("[Nightfall] /nfsus fake [chunkX chunkZ], /nfsus list, /nfsus remove <nummer>, /nfsus clear"));
          return 1;
        })
        .then(ClientCommandManager.literal("fake")
          .executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) { ctx.getSource().sendFeedback(Component.literal("[Nightfall] Keine aktive Welt.")); return 0; }
            int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
            String result = OGBaseFinder.learnFake(mc, cx, cz);
            ctx.getSource().sendFeedback(Component.literal("[Nightfall] Chunk " + cx + ", " + cz + ": " + result));
            return result.startsWith("Fake-Base-Muster gespeichert") ? 1 : 0;
          })
          .then(ClientCommandManager.argument("chunkX", IntegerArgumentType.integer())
            .then(ClientCommandManager.argument("chunkZ", IntegerArgumentType.integer()).executes(ctx -> {
              int cx = IntegerArgumentType.getInteger(ctx, "chunkX");
              int cz = IntegerArgumentType.getInteger(ctx, "chunkZ");
              String result = OGBaseFinder.learnFake(Minecraft.getInstance(), cx, cz);
              ctx.getSource().sendFeedback(Component.literal("[Nightfall] Chunk " + cx + ", " + cz + ": " + result));
              return result.startsWith("Fake-Base-Muster gespeichert") ? 1 : 0;
            }))))
        .then(ClientCommandManager.literal("list").executes(ctx -> {
          ctx.getSource().sendFeedback(Component.literal("[Nightfall] Lokal gespeicherte Fake-Base-Muster: " + nightfall.analyzer.FakeBaseMemory.size() + "/32"));
          return 1;
        }))
        .then(ClientCommandManager.literal("remove")
          .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1)).executes(ctx -> {
            int number = IntegerArgumentType.getInteger(ctx, "number");
            boolean removed = nightfall.analyzer.FakeBaseMemory.remove(number);
            ctx.getSource().sendFeedback(Component.literal(removed ? "[Nightfall] Fake-Base-Muster #" + number + " entfernt." : "[Nightfall] Nummer ungueltig oder Speichern fehlgeschlagen."));
            return removed ? 1 : 0;
          })))
        .then(ClientCommandManager.literal("clear").executes(ctx -> {
          boolean cleared = nightfall.analyzer.FakeBaseMemory.clear();
          ctx.getSource().sendFeedback(Component.literal(cleared ? "[Nightfall] Alle Fake-Base-Muster geloescht." : "[Nightfall] Muster konnten nicht geloescht werden."));
          return cleared ? 1 : 0;
        }))));
  }

  public static void click(boolean on) {
    if (!sound || Modules.SOUND == null || !Modules.SOUND.on("Enabled")) return;
    int style = Modules.SOUND.s("Style").i();
    float volume = (float) Modules.SOUND.s("Volume").d();
    SoundEvent event = switch (style) {
      case 1 -> SoundEvents.LEVER_CLICK;
      case 2 -> SoundEvents.NOTE_BLOCK_PLING.value();
      default -> SoundEvents.UI_BUTTON_CLICK.value();
    };
    float pitch = switch (style) {
      case 0 -> on ? 0.95f : 0.82f; // Soft
      case 1 -> on ? 1.10f : 0.92f; // Mechanical
      case 2 -> on ? 1.55f : 1.25f; // Digital
      default -> on ? 1.35f : 1.15f; // Minimal
    };
    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
  }
  private static void tick(Minecraft mc) {
    Esp.tick(mc); OGBaseFinder.tick(mc); PlayerWatch.tick(mc);
    if (mc.player == null) { Cam.freelook = Cam.freecam = false; Modules.FREELOOK.enabled = Modules.FREECAM.enabled = false; return; }
    long w = mc.getWindow().handle();
    for (Module m : Modules.ALL) {
      if (m.key < 0) continue;
      boolean d = GLFW.glfwGetKey(w, m.key) == GLFW.GLFW_PRESS, was = down.getOrDefault(m, false);
      down.put(m, d);
      if (mc.screen != null) { if (m.hold && m.enabled && !d) m.setEnabled(false); continue; }
      if (m.hold) { if (d != was) m.setEnabled(d); } else if (d && !was) m.toggle();
    }
  }
}
