package ru.warland.qa;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import ru.warland.economy.InventorySnapshot;
import ru.warland.economy.MarketStackCodec;

/** ONLY for a standalone QA mod manifest in a fresh loopback runtime, never production. */
public final class MarketCodecRuntimeProbe implements ModInitializer {
    private int checks;
    private String step = "startup";
    private void check(boolean value) { checks++; if (!value) throw new AssertionError("probe assertion"); }
    private void rejects(Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected);
    }
    @Override public void onInitialize() {
        if (!Boolean.getBoolean("warland.codecProbe")) throw new IllegalStateException("QA-only opt-in required");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (!Set.of("127.0.0.1", "::1").contains(server.getServerIp())) throw new IllegalStateException("Loopback required");
                var codec = new MarketStackCodec(server.getRegistryManager());
                step = "empty";
                check(codec.encode(ItemStack.EMPTY).empty());
                check(codec.decode(InventorySnapshot.StackData.EMPTY).isEmpty());
                rejects(() -> codec.store(ItemStack.EMPTY));
                var stone = new ItemStack(Items.STONE, 64);
                var named = new ItemStack(Items.DIAMOND, 5);
                named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Synthetic name | : \"probe\""));
                var sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.setDamage(17);
                sword.addEnchantment(server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 2);
                var container = new ItemStack(Items.SHULKER_BOX);
                container.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(named.copy(), sword.copy())));
                var pearls = new ItemStack(Items.ENDER_PEARL, 16);
                var overridden = new ItemStack(Items.STONE, 99);
                overridden.set(DataComponentTypes.MAX_STACK_SIZE, 99);
                var single = new ItemStack(Items.STICK, 1);
                single.set(DataComponentTypes.MAX_STACK_SIZE, 1);
                List<ItemStack> examples = List.of(stone, named, sword, container, pearls, overridden, single);
                List<String> envelopes = new ArrayList<>();
                for (int i = 0; i < examples.size(); i++) {
                    step = "roundtrip-" + i;
                    ItemStack source = examples.get(i); var encoded = codec.encode(source);
                    check(ItemStack.areEqual(source, codec.decode(encoded)));
                    String stored = codec.store(source); envelopes.add(stored);
                    check(ItemStack.areEqual(source, codec.restore(stored)));
                    check(codec.store(codec.restore(stored)).equals(stored));
                    var copy = codec.decode(encoded); copy.setCount(0);
                    check(source.getCount() > 0 && encoded.count() > 0);
                    rejects(() -> codec.restore(stored.replace("\"schema\":1", "\"schema\":2")));
                    rejects(() -> codec.restore(stored.replace("\"minecraft\":\"1.21.11\"", "\"minecraft\":\"1.21.10\"")));
                    rejects(() -> codec.restore(stored.replace("\"count\":", "\"extra\":true,\"count\":")));
                    rejects(() -> codec.restore(stored.replace("\"count\":", "\"count\":1,\"count\":")));
                    rejects(() -> codec.restore(stored.replace("\"max_count\":", "\"max_count\":0,\"shadow\":")));
                }
                step = "invalid-input";
                rejects(() -> codec.restore("x".repeat(65537)));
                rejects(() -> codec.restore("[".repeat(33) + "0" + "]".repeat(33)));
                rejects(() -> codec.encode(new ItemStack(Items.STONE, 65)));
                rejects(() -> codec.restore(envelopes.getFirst().replace("minecraft:stone", "minecraft:unknown_codec_probe_item")));
                try { codec.restore("private-synthetic-canary-not-for-diagnostics"); throw new AssertionError("accepted bad input"); }
                catch (IllegalArgumentException expected) {
                    check(expected.getMessage().equals("Invalid, lossy or oversized market item"));
                    check(expected.getCause() == null);
                }
                step = "real-stack-plans";
                var slots = new ArrayList<ItemStack>(Collections.nCopies(41, ItemStack.EMPTY));
                slots.set(0, named.copy()); slots.set(40, sword.copy());
                var snapshot = codec.capture(slots, 0, ItemStack.EMPTY);
                var deposit = snapshot.deposit(0, 3);
                check(deposit.transferred().count() == 3);
                check(ItemStack.areItemsAndComponentsEqual(named, codec.decode(deposit.transferred())));
                check(deposit.after().slots().get(40).equals(snapshot.slots().get(40)));
                check(deposit.after().delivery(deposit.transferred()).after().equals(snapshot));
                rejects(() -> snapshot.deposit(40, 1));
                slots.set(0, ItemStack.EMPTY);
                check(snapshot.slots().get(0).count() == 5);
                step = "persistent-envelope";
                Path marker = FabricLoader.getInstance().getGameDir().resolve("codec-probe-envelopes.txt");
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Unsafe probe marker");
                    if (Files.size(marker) > 1024 * 1024) throw new IllegalStateException("Oversized probe marker");
                    List<String> previous = Files.readAllLines(marker);
                    check(previous.equals(envelopes));
                    for (int i = 0; i < examples.size(); i++) check(ItemStack.areEqual(examples.get(i), codec.restore(previous.get(i))));
                    System.out.println("WARLAND_CODEC_PROBE_RESTART_PASS");
                } else Files.write(marker, envelopes, StandardOpenOption.CREATE_NEW);
                System.out.println("WARLAND_CODEC_PROBE_PASS checks=" + checks);
            } catch (Throwable failure) {
                System.out.println("WARLAND_CODEC_PROBE_FAIL step=" + step + " type=" + failure.getClass().getSimpleName());
            } finally { server.stop(false); }
        });
    }
}
