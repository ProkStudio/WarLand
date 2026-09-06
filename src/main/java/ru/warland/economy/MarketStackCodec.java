package ru.warland.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;

/** Registry-aware persistent stack codec. Call only on the owning server thread. */
public final class MarketStackCodec {
    private final RegistryWrapper.WrapperLookup registries;
    public MarketStackCodec(RegistryWrapper.WrapperLookup registries) {
        this.registries = Objects.requireNonNull(registries);
    }

    public InventorySnapshot.StackData encode(ItemStack stack) {
        try { return encodeValidated(stack); }
        catch (RuntimeException rejected) { throw invalid(); }
    }
    private InventorySnapshot.StackData encodeValidated(ItemStack stack) {
        Objects.requireNonNull(stack);
        if (stack.isEmpty()) {
            if (stack.getCount() != 0) throw invalid();
            return InventorySnapshot.StackData.EMPTY;
        }
        if (stack.getCount() < 1 || stack.getCount() > stack.getMaxCount() || stack.getMaxCount() > 99) throw invalid();
        ItemStack unit = stack.copyWithCount(1);
        JsonElement json = ItemStack.VALIDATED_CODEC.encodeStart(registries.getOps(JsonOps.INSTANCE), unit).getOrThrow();
        String payload = canonicalJson(json);
        ItemStack roundTrip = ItemStack.VALIDATED_CODEC.parse(registries.getOps(JsonOps.INSTANCE), json).getOrThrow();
        if (!ItemStack.areItemsAndComponentsEqual(unit, roundTrip) || roundTrip.getCount() != 1) throw invalid();
        return new InventorySnapshot.StackData(payload, stack.getCount(), stack.getMaxCount());
    }

    public ItemStack decode(InventorySnapshot.StackData encoded) {
        try { return decodeValidated(encoded); }
        catch (RuntimeException rejected) { throw invalid(); }
    }
    private ItemStack decodeValidated(InventorySnapshot.StackData encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.empty()) return ItemStack.EMPTY;
        String payload = encoded.unitPayload();
        validateJsonBounds(payload);
        JsonElement json = JsonParser.parseString(payload);
        if (!json.isJsonObject() || !canonicalJson(json).equals(payload)) throw invalid();
        ItemStack unit = ItemStack.VALIDATED_CODEC.parse(registries.getOps(JsonOps.INSTANCE), json).getOrThrow();
        if (unit.isEmpty() || unit.getCount() != 1 || unit.getMaxCount() != encoded.maxCount()) throw invalid();
        ItemStack result = unit.copyWithCount(encoded.count());
        // Reject unknown/duplicate fields, lossy components and noncanonical numeric encodings.
        if (!encode(result).equals(encoded)) throw invalid();
        return result;
    }

    /** Persistent market_items.stack envelope, pinned to this schema and Minecraft version. */
    public String store(ItemStack stack) {
        try {
            InventorySnapshot.StackData value = encode(stack);
            if (value.empty()) throw invalid();
            JsonObject root = new JsonObject();
            root.addProperty("schema", 1);
            root.addProperty("minecraft", "1.21.11");
            root.addProperty("count", value.count());
            root.addProperty("max_count", value.maxCount());
            root.add("unit", JsonParser.parseString(value.unitPayload()));
            return canonicalJson(root);
        } catch (RuntimeException rejected) { throw invalid(); }
    }

    public ItemStack restore(String stored) {
        try {
            validateJsonBounds(stored);
            JsonObject root = JsonParser.parseString(stored).getAsJsonObject();
            if (root.size() != 5 || root.get("schema").getAsInt() != 1
                    || !"1.21.11".equals(root.get("minecraft").getAsString())
                    || !canonicalJson(root).equals(stored)) throw invalid();
            var value = new InventorySnapshot.StackData(canonicalJson(root.get("unit")),
                    root.get("count").getAsInt(), root.get("max_count").getAsInt());
            ItemStack result = decode(value);
            if (!store(result).equals(stored)) throw invalid();
            return result;
        } catch (RuntimeException rejected) { throw invalid(); }
    }

    /** Caller supplies every vanilla inventory slot and cursor from one server-thread observation. */
    public InventorySnapshot capture(java.util.List<ItemStack> slots, int selected, ItemStack cursor) {
        if (slots.size() < InventorySnapshot.MAIN_SLOTS || slots.size() > InventorySnapshot.MAX_SLOTS) throw invalid();
        return new InventorySnapshot(slots.stream().map(this::encode).toList(), selected, encode(cursor));
    }

    static String canonicalJson(JsonElement json) {
        String result = sorted(Objects.requireNonNull(json), 0, new int[]{0}).toString();
        validateJsonBounds(result);
        return result;
    }
    private static JsonElement sorted(JsonElement value, int depth, int[] nodes) {
        if (depth > 32 || ++nodes[0] > 8192) throw invalid();
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
                result.add(key, sorted(value.getAsJsonObject().get(key), depth + 1, nodes));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray()) result.add(sorted(entry, depth + 1, nodes));
            return result;
        }
        return value.deepCopy();
    }
    /** Bound nesting before asking a JSON parser to allocate or recurse over stored input. */
    static void validateJsonBounds(String input) {
        if (input == null || input.isBlank() || input.length() > InventorySnapshot.MAX_PAYLOAD) throw invalid();
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 32) throw invalid(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw invalid(); }
        }
        if (quoted || depth != 0) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        // Never echo player item components into public diagnostics.
        return new IllegalArgumentException("Invalid, lossy or oversized market item");
    }
}
