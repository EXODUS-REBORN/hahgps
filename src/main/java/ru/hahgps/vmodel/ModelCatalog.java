package ru.hahgps.vmodel;

import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelCatalog {

    private final HahGpsPlugin plugin;
    private final Map<String, ModelDefinition> cache = new ConcurrentHashMap<>();

    public ModelCatalog(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public int size() {
        return cache.size();
    }

    public Set<String> keys() {
        return cache.keySet();
    }

    public Collection<ModelDefinition> all() {
        return List.copyOf(cache.values());
    }

    public void replaceAll(List<ModelDefinition> definitions) {
        Map<String, ModelDefinition> next = new ConcurrentHashMap<>();
        if (definitions != null) {
            for (ModelDefinition def : definitions) {
                if (def != null && def.key() != null && !def.key().isBlank()) {
                    next.put(normalize(def.key()), def);
                }
            }
        }
        cache.clear();
        cache.putAll(next);
    }

    public void put(ModelDefinition definition) {
        if (definition != null && definition.key() != null && !definition.key().isBlank()) {
            cache.put(normalize(definition.key()), definition);
        }
    }

    public void putAll(Collection<ModelDefinition> definitions) {
        if (definitions == null) return;
        for (ModelDefinition def : definitions) {
            put(def);
        }
    }

    public void drop(String key) {
        if (key != null) {
            cache.remove(normalize(key));
        }
    }

    public void clear() {
        cache.clear();
    }

    public ModelDefinition get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return cache.get(normalize(key));
    }

    public ModelDefinition require(String key) {
        ModelDefinition found = get(key);
        if (found != null) {
            return found;
        }
        PanelClient panel = plugin.getVmodels() == null ? null : plugin.getVmodels().panel();
        if (panel == null || !panel.configured()) {
            return null;
        }
        byte[] body = panel.get("/api/minecraft/v1/vmodels/?key=" + PanelClient.encode(normalize(key)));
        if (body == null) {
            return null;
        }
        Object payload = Json.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        ModelDefinition parsed = ModelParse.definition(Json.object(payload).get("definition"));
        if (parsed != null) {
            put(parsed);
        }
        return parsed;
    }

    public List<ModelDefinition> local() {
        List<ModelDefinition> out = new ArrayList<>(cache.values());
        out.sort(java.util.Comparator.comparing(ModelDefinition::key));
        return out;
    }

    private static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
