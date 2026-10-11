package ru.ruby.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Replace the model of the local player (you). */
    public boolean replaceSelf = true;
    /** Replace the model of every player. */
    public boolean replaceAll = false;
    /** Additional player names whose model should be replaced. */
    public List<String> players = new ArrayList<>();
    /** Do not render worn armor on the custom model. */
    public boolean hideArmor = true;
    /** Render the custom model arm in first person instead of the vanilla one. */
    public boolean firstPersonArm = true;
    /** Overall model scale multiplier. */
    public float scale = 1.0f;
    /** Apply vanilla-like walking / head / attack animations to bones named Head, Body, RightArm, LeftArm, RightLeg, LeftLeg. */
    public boolean animate = true;

    public static Config load(Path path) {
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) {
                        if (c.players == null) c.players = new ArrayList<>();
                        return c;
                    }
                }
            }
            Config c = new Config();
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(c, w);
            }
            return c;
        } catch (Exception e) {
            RubySkinClient.LOGGER.error("[RubySkin] cannot read config, using defaults", e);
            return new Config();
        }
    }
}
