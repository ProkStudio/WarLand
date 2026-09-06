package ru.warland.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable server-produced snapshot. Planning never writes to Minecraft or the database. */
public record InventorySnapshot(List<InventorySnapshot.StackData> slots, int selected,
                                InventorySnapshot.StackData cursor) {
    public static final int MAIN_SLOTS = 36;
    public static final int MAX_SLOTS = 64;
    public static final int MAX_PAYLOAD = 65_536;
    public static final int MAX_SNAPSHOT = 262_144;

    /** unitPayload is a canonical, registry-validated count-one ItemStack, not client input. */
    public record StackData(String unitPayload, int count, int maxCount) {
        public static final StackData EMPTY = new StackData("", 0, 0);
        public StackData {
            Objects.requireNonNull(unitPayload);
            if (count == 0) {
                if (!unitPayload.isEmpty() || maxCount != 0) throw invalid();
            } else if (unitPayload.isBlank() || unitPayload.length() > MAX_PAYLOAD
                    || maxCount < 1 || maxCount > 99 || count < 1 || count > maxCount) throw invalid();
        }
        public boolean empty() { return count == 0; }
        public StackData withCount(int amount) {
            if (amount == 0) return EMPTY;
            return new StackData(unitPayload, amount, maxCount);
        }
        public boolean compatible(StackData other) {
            return !empty() && !other.empty() && maxCount == other.maxCount && unitPayload.equals(other.unitPayload);
        }
    }

    public record Plan(InventorySnapshot before, InventorySnapshot after, StackData transferred) {
        public Plan {
            Objects.requireNonNull(before); Objects.requireNonNull(after); Objects.requireNonNull(transferred);
            if (transferred.empty() || before.equals(after)) throw invalid();
        }
    }

    public InventorySnapshot {
        slots = List.copyOf(slots);
        Objects.requireNonNull(cursor);
        if (slots.size() < MAIN_SLOTS || slots.size() > MAX_SLOTS || selected < 0 || selected >= 9) throw invalid();
        // Enforce the repository's payload bound at construction, before any intent could be prepared.
        serialized(slots, selected, cursor);
    }

    public String canonical() { return serialized(slots, selected, cursor); }

    /** Deposit from one ordinary storage slot; armor/offhand/equipment are never selected. */
    public Plan deposit(int slot, int quantity) {
        requireClearCursor();
        if (slot < 0 || slot >= MAIN_SLOTS || quantity < 1) throw invalid();
        StackData original = slots.get(slot);
        if (original.empty() || quantity > original.count()) throw invalid();
        List<StackData> after = new ArrayList<>(slots);
        after.set(slot, original.withCount(original.count() - quantity));
        return new Plan(this, new InventorySnapshot(after, selected, cursor), original.withCount(quantity));
    }

    /** Merge compatible main stacks, then use empty main slots. No drop-on-full fallback. */
    public Plan delivery(StackData item) {
        requireClearCursor(); Objects.requireNonNull(item);
        if (item.empty()) throw invalid();
        List<StackData> after = new ArrayList<>(slots);
        int remaining = item.count();
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            StackData old = after.get(slot);
            if (!old.compatible(item)) continue;
            int moved = Math.min(remaining, old.maxCount() - old.count());
            if (moved > 0) { after.set(slot, old.withCount(old.count() + moved)); remaining -= moved; }
        }
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            if (!after.get(slot).empty()) continue;
            int moved = Math.min(remaining, item.maxCount());
            after.set(slot, item.withCount(moved)); remaining -= moved;
        }
        if (remaining != 0) throw new IllegalArgumentException("Insufficient inventory space");
        return new Plan(this, new InventorySnapshot(after, selected, cursor), item);
    }

    private void requireClearCursor() {
        if (!cursor.empty()) throw new IllegalArgumentException("Inventory cursor must be empty");
    }
    private static String serialized(List<StackData> slots, int selected, StackData cursor) {
        StringBuilder result = new StringBuilder("WL_INV_1|").append(selected).append('|').append(slots.size()).append('|');
        for (StackData item : slots) append(result, item);
        append(result, cursor);
        return result.toString();
    }
    private static void append(StringBuilder result, StackData item) {
        // Length framing is unambiguous even when a component string contains separators/newlines.
        long size = (long) result.length() + item.unitPayload().length() + 32;
        if (size > MAX_SNAPSHOT) throw new IllegalArgumentException("Inventory snapshot too large");
        result.append(item.count()).append(':').append(item.maxCount()).append(':')
                .append(item.unitPayload().length()).append(':').append(item.unitPayload()).append('|');
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid inventory snapshot or plan"); }
}
