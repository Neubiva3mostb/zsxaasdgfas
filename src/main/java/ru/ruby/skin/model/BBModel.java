package ru.ruby.skin.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal parser for Blockbench .bbmodel files (cube based "free"/"bedrock"/"java" formats).
 * Only cubes are supported (meshes are ignored).
 */
public class BBModel {
    public String name = "model";
    public int resW = 64, resH = 64;
    public final List<Texture> textures = new ArrayList<>();
    public final List<Bone> roots = new ArrayList<>();

    public static class Texture {
        public String name;
        public byte[] png;      // decoded PNG bytes (may be null if texture has no embedded source)
        public float uvW, uvH;  // uv space size of this texture
    }

    public static class Face {
        public int texture;     // index into textures, -1 = none
        public float u0, v0, u1, v1; // normalized 0..1
        public int rotation;    // 0 / 90 / 180 / 270
    }

    public static class Cube {
        public String name;
        public float[] from, to;
        public float inflate;
        public float[] origin;
        public float[] rotation;
        /** indexed by BBFace ordinal: north, east, south, west, up, down */
        public final Face[] faces = new Face[6];
        public boolean visible = true;
    }

    public static class Bone {
        public String name;
        public float[] origin = {0, 0, 0};
        public float[] rotation = {0, 0, 0};
        public boolean visible = true;
        public final List<Bone> children = new ArrayList<>();
        public final List<Cube> cubes = new ArrayList<>();
    }

    public enum BBFace { NORTH, EAST, SOUTH, WEST, UP, DOWN }

    public int boneCount() { int n = 0; for (Bone b : roots) n += count(b); return n; }
    private int count(Bone b) { int n = 1; for (Bone c : b.children) n += count(c); return n; }
    public int cubeCount() { int n = 0; for (Bone b : roots) n += cubes(b); return n; }
    private int cubes(Bone b) { int n = b.cubes.size(); for (Bone c : b.children) n += cubes(c); return n; }

    public static BBModel parse(InputStream in) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        BBModel m = new BBModel();
        if (root.has("name")) m.name = root.get("name").getAsString();
        if (root.has("resolution")) {
            JsonObject res = root.getAsJsonObject("resolution");
            m.resW = res.get("width").getAsInt();
            m.resH = res.get("height").getAsInt();
        }

        // textures
        if (root.has("textures")) {
            for (JsonElement te : root.getAsJsonArray("textures")) {
                JsonObject t = te.getAsJsonObject();
                Texture tex = new Texture();
                tex.name = optString(t, "name", "texture");
                tex.uvW = t.has("uv_width") ? t.get("uv_width").getAsFloat() : m.resW;
                tex.uvH = t.has("uv_height") ? t.get("uv_height").getAsFloat() : m.resH;
                if (tex.uvW <= 0) tex.uvW = m.resW;
                if (tex.uvH <= 0) tex.uvH = m.resH;
                if (t.has("source") && !t.get("source").isJsonNull()) {
                    String src = t.get("source").getAsString();
                    int comma = src.indexOf(',');
                    if (src.startsWith("data:") && comma > 0) {
                        tex.png = Base64.getDecoder().decode(src.substring(comma + 1));
                    }
                }
                m.textures.add(tex);
            }
        }

        // elements
        Map<String, Cube> elements = new HashMap<>();
        if (root.has("elements")) {
            for (JsonElement ee : root.getAsJsonArray("elements")) {
                JsonObject e = ee.getAsJsonObject();
                String type = optString(e, "type", "cube");
                if (!"cube".equals(type)) continue;
                Cube c = new Cube();
                c.name = optString(e, "name", "cube");
                c.from = vec3(e.get("from"));
                c.to = vec3(e.get("to"));
                c.inflate = e.has("inflate") ? e.get("inflate").getAsFloat() : 0f;
                c.origin = e.has("origin") ? vec3(e.get("origin")) : new float[]{0, 0, 0};
                c.rotation = e.has("rotation") && e.get("rotation").isJsonArray() ? vec3(e.get("rotation")) : new float[]{0, 0, 0};
                c.visible = !(e.has("visibility") && !e.get("visibility").getAsBoolean());
                if (e.has("export") && !e.get("export").getAsBoolean()) c.visible = false;
                JsonObject faces = e.getAsJsonObject("faces");
                if (faces != null) {
                    for (BBFace f : BBFace.values()) {
                        String key = f.name().toLowerCase();
                        if (!faces.has(key) || faces.get(key).isJsonNull()) continue;
                        JsonObject fo = faces.getAsJsonObject(key);
                        if (!fo.has("texture") || fo.get("texture").isJsonNull()) continue;
                        Face face = new Face();
                        JsonElement texEl = fo.get("texture");
                        if (texEl.isJsonPrimitive() && texEl.getAsJsonPrimitive().isNumber()) {
                            face.texture = texEl.getAsInt();
                        } else {
                            face.texture = m.textures.isEmpty() ? -1 : 0; // uuid reference or similar - fallback
                            String ref = texEl.getAsString();
                            for (int i = 0; i < m.textures.size(); i++) if (ref.equals(m.textures.get(i).name)) face.texture = i;
                        }
                        if (face.texture < 0 || face.texture >= m.textures.size()) continue;
                        Texture tex = m.textures.get(face.texture);
                        JsonArray uv = fo.getAsJsonArray("uv");
                        face.u0 = uv.get(0).getAsFloat() / tex.uvW;
                        face.v0 = uv.get(1).getAsFloat() / tex.uvH;
                        face.u1 = uv.get(2).getAsFloat() / tex.uvW;
                        face.v1 = uv.get(3).getAsFloat() / tex.uvH;
                        face.rotation = fo.has("rotation") ? fo.get("rotation").getAsInt() : 0;
                        c.faces[f.ordinal()] = face;
                    }
                }
                elements.put(e.get("uuid").getAsString(), c);
            }
        }

        // groups (format >= 4.10 keeps group data separately from the outliner tree)
        Map<String, JsonObject> groups = new HashMap<>();
        if (root.has("groups")) {
            for (JsonElement ge : root.getAsJsonArray("groups")) {
                JsonObject g = ge.getAsJsonObject();
                groups.put(g.get("uuid").getAsString(), g);
            }
        }

        if (root.has("outliner")) {
            for (JsonElement oe : root.getAsJsonArray("outliner")) {
                if (oe.isJsonObject()) {
                    m.roots.add(parseBone(oe.getAsJsonObject(), groups, elements));
                } else {
                    Cube c = elements.get(oe.getAsString());
                    if (c != null) {
                        Bone b = new Bone();
                        b.name = c.name;
                        b.cubes.add(c);
                        m.roots.add(b);
                    }
                }
            }
        }
        return m;
    }

    private static Bone parseBone(JsonObject node, Map<String, JsonObject> groups, Map<String, Cube> elements) {
        JsonObject data = node;
        if (!node.has("name") && node.has("uuid")) {
            JsonObject g = groups.get(node.get("uuid").getAsString());
            if (g != null) data = g;
        }
        Bone b = new Bone();
        b.name = optString(data, "name", "bone");
        if (data.has("origin")) b.origin = vec3(data.get("origin"));
        if (data.has("rotation") && data.get("rotation").isJsonArray()) b.rotation = vec3(data.get("rotation"));
        if (data.has("visibility") && !data.get("visibility").getAsBoolean()) b.visible = false;
        if (data.has("export") && !data.get("export").getAsBoolean()) b.visible = false;
        JsonArray children = node.has("children") ? node.getAsJsonArray("children") : null;
        if (children != null) {
            for (JsonElement ce : children) {
                if (ce.isJsonObject()) {
                    b.children.add(parseBone(ce.getAsJsonObject(), groups, elements));
                } else {
                    Cube c = elements.get(ce.getAsString());
                    if (c != null) b.cubes.add(c);
                }
            }
        }
        return b;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static float[] vec3(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }
}
