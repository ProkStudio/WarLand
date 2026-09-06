package ru.warland.economy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.economy.InventorySnapshot.StackData;

class InventorySnapshotTest {
    private static final StackData STONE = new StackData("stone-components", 64, 64);
    private static List<StackData> empty() { return new ArrayList<>(Collections.nCopies(41, StackData.EMPTY)); }
    private static InventorySnapshot snapshot(List<StackData> slots) { return new InventorySnapshot(slots, 0, StackData.EMPTY); }
    private static int count(InventorySnapshot s) { return s.slots().stream().mapToInt(StackData::count).sum(); }

    @Test void copiesInputAndExposesImmutableSlots() {
        var slots = empty(); var s = snapshot(slots); slots.set(0, STONE);
        assertTrue(s.slots().get(0).empty());
        assertThrows(UnsupportedOperationException.class, () -> s.slots().set(0, STONE));
    }
    @Test void validatesCountsPayloadAndSlots() {
        assertThrows(IllegalArgumentException.class, () -> new StackData("x", 0, 64));
        assertThrows(IllegalArgumentException.class, () -> new StackData("", 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new StackData("x", -1, 64));
        assertThrows(IllegalArgumentException.class, () -> new StackData("x", 65, 64));
        assertThrows(IllegalArgumentException.class, () -> new StackData("x", 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new StackData("x".repeat(65537), 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(List.of(), 0, StackData.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(empty(), 9, StackData.EMPTY));
    }
    @Test void snapshotSizeBoundAppliesBeforePlanning() {
        var slots = empty();
        for (int i = 0; i < 5; i++) slots.set(i, new StackData("x".repeat(65536), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> snapshot(slots));
    }
    @Test void depositPreservesAllOtherSlotsAndOriginal() {
        var slots = empty(); slots.set(8, STONE); slots.set(40, new StackData("offhand", 1, 1));
        var s = snapshot(slots); var p = s.deposit(8, 12);
        assertSame(s, p.before()); assertEquals(64, s.slots().get(8).count());
        assertEquals(52, p.after().slots().get(8).count()); assertEquals(12, p.transferred().count());
        for (int i = 0; i < 41; i++) if (i != 8) assertEquals(s.slots().get(i), p.after().slots().get(i));
        assertNotEquals(s.canonical(), p.after().canonical());
    }
    @Test void fullDepositUsesCanonicalEmpty() {
        var slots = empty(); slots.set(0, STONE);
        assertEquals(StackData.EMPTY, snapshot(slots).deposit(0, 64).after().slots().get(0));
    }
    @Test void invalidDepositNeverMutatesInput() {
        var slots = empty(); slots.set(0, STONE); var s = snapshot(slots); String before = s.canonical();
        for (int slot : new int[]{-1, 36, 40}) assertThrows(IllegalArgumentException.class, () -> s.deposit(slot, 1));
        for (int quantity : new int[]{-1, 0, 65, Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> s.deposit(0, quantity));
        assertThrows(IllegalArgumentException.class, () -> s.deposit(1, 1));
        assertEquals(before, s.canonical());
    }
    @Test void nonemptyCursorBlocksBothDirections() {
        var s = new InventorySnapshot(empty(), 0, STONE);
        assertThrows(IllegalArgumentException.class, () -> s.deposit(0, 1));
        assertThrows(IllegalArgumentException.class, () -> s.delivery(STONE));
    }
    @Test void deliveryMergesThenUsesEmptyStorage() {
        var slots = empty(); slots.set(0, STONE.withCount(60)); slots.set(1, STONE.withCount(63));
        var s = snapshot(slots); var p = s.delivery(STONE.withCount(10));
        assertEquals(64, p.after().slots().get(0).count()); assertEquals(64, p.after().slots().get(1).count());
        assertEquals(5, p.after().slots().get(2).count()); assertEquals(count(s) + 10, count(p.after()));
    }
    @Test void differingComponentsDoNotMerge() {
        var slots = empty(); slots.set(0, new StackData("named-stone", 1, 64));
        var p = snapshot(slots).delivery(STONE.withCount(1));
        assertEquals(slots.get(0), p.after().slots().get(0)); assertEquals(STONE.withCount(1), p.after().slots().get(1));
    }
    @Test void maxStackRuleIsPartOfCompatibility() {
        var slots = empty(); slots.set(0, new StackData("stone-components", 1, 16));
        var p = snapshot(slots).delivery(STONE.withCount(1));
        assertEquals(1, p.after().slots().get(0).count()); assertEquals(1, p.after().slots().get(1).count());
    }
    @Test void fullMainInventoryCannotUseEmptyEquipmentSlots() {
        var slots = empty(); for (int i = 0; i < 36; i++) slots.set(i, STONE);
        var s = snapshot(slots); String before = s.canonical();
        assertThrows(IllegalArgumentException.class, () -> s.delivery(STONE.withCount(1)));
        assertEquals(before, s.canonical()); assertTrue(s.slots().get(40).empty());
    }
    @Test void partialFitFailsWithoutPublishingPartialMutation() {
        var slots = empty(); for (int i = 0; i < 36; i++) slots.set(i, STONE);
        slots.set(0, STONE.withCount(63)); var s = snapshot(slots); String before = s.canonical();
        assertThrows(IllegalArgumentException.class, () -> s.delivery(STONE.withCount(2)));
        assertEquals(before, s.canonical());
    }
    @Test void cursorSelectionEquipmentAndFramingAreInSnapshot() {
        var slots = empty(); var s = snapshot(slots);
        assertNotEquals(s.canonical(), new InventorySnapshot(slots, 1, StackData.EMPTY).canonical());
        slots.set(40, new StackData("a|1:2:\n", 1, 1));
        assertNotEquals(s.canonical(), snapshot(slots).canonical());
        assertEquals(snapshot(slots).canonical(), snapshot(new ArrayList<>(slots)).canonical());
    }
    @Test void exhaustiveSingleStackConservationAndRoundTrip() {
        for (int initial = 1; initial <= 64; initial++) for (int quantity = 1; quantity <= initial; quantity++) {
            var slots = empty(); slots.set(0, STONE.withCount(initial)); var s = snapshot(slots);
            var out = s.deposit(0, quantity);
            assertEquals(initial, count(out.after()) + out.transferred().count());
            assertEquals(s, out.after().delivery(out.transferred()).after());
        }
    }
}
