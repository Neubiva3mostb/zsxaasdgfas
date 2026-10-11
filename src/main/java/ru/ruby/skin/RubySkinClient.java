package ru.ruby.skin;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ruby.skin.model.BBModel;
import ru.ruby.skin.model.BBModelRenderer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class RubySkinClient implements ClientModInitializer {
    public static final String MOD_ID = "rubyskin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Config config = new Config();
    private static BBModelRenderer renderer;
    private static boolean loadAttempted = false;

    @Override
    public void onInitializeClient() {
        config = Config.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));
        LOGGER.info("[RubySkin] loaded, replaceSelf={}, replaceAll={}, players={}",
                config.replaceSelf, config.replaceAll, config.players);
    }

    public static Config config() {
        return config;
    }

    /** Lazily parsed model (textures must be uploaded on the render thread, so we do it on first render). */
    public static BBModelRenderer renderer() {
        if (renderer == null && !loadAttempted) {
            loadAttempted = true;
            try {
                BBModel model;
                Path external = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("model.bbmodel");
                if (Files.exists(external)) {
                    LOGGER.info("[RubySkin] loading external model {}", external);
                    try (InputStream in = Files.newInputStream(external)) {
                        model = BBModel.parse(in);
                    }
                } else {
                    try (InputStream in = RubySkinClient.class.getResourceAsStream("/assets/" + MOD_ID + "/models/ruby.bbmodel")) {
                        if (in == null) throw new IllegalStateException("bundled model not found");
                        model = BBModel.parse(in);
                    }
                }
                renderer = new BBModelRenderer(model);
                LOGGER.info("[RubySkin] model '{}' loaded: {} bones, {} cubes, {} textures",
                        model.name, model.boneCount(), model.cubeCount(), model.textures.size());
            } catch (Exception e) {
                LOGGER.error("[RubySkin] failed to load model", e);
            }
        }
        return renderer;
    }

    /** Allows re-loading the model (e.g. after editing config/rubyskin/model.bbmodel) by pressing F3+T. */
    public static void invalidate() {
        if (renderer != null) renderer.close();
        renderer = null;
        loadAttempted = false;
    }

    public static boolean shouldReplace(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        if (config.replaceAll) return true;
        LocalPlayer self = mc.player;
        if (config.replaceSelf && self != null && self.getId() == entityId) return true;
        if (!config.players.isEmpty()) {
            Entity e = mc.level.getEntity(entityId);
            if (e instanceof Player p) {
                String name = p.getGameProfile().name();
                for (String n : config.players) {
                    if (n.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }
}
