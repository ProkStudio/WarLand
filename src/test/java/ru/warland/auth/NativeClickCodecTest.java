package ru.warland.auth;

import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeClickCodecTest {
    @Test void presentPayloadUsesLengthPrefixAndRoundTrips() {
        Identifier id = Identifier.of("warland", "auth/login");
        NbtCompound nbt = new NbtCompound();
        nbt.putString("nonce", "11111111-2222-3333-4444-555555555555");
        nbt.putString("password", "synthetic-test-only");
        nbt.putString("confirmation", "");
        nbt.putString("owner", "");
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            CustomClickActionC2SPacket.CODEC.encode(buffer, new CustomClickActionC2SPacket(id, Optional.of(nbt)));
            assertEquals(id, buffer.readIdentifier());
            int length = buffer.readVarInt();
            assertEquals(length, buffer.readableBytes());
            assertTrue(length > 1, "The prefix is a byte length, not an optional-presence boolean");
            assertEquals(10, buffer.readUnsignedByte(), "Unnamed NBT compound begins immediately after the length");
            buffer.readerIndex(0);
            var decoded = CustomClickActionC2SPacket.CODEC.decode(buffer);
            assertEquals(id, decoded.id());
            assertEquals(nbt, decoded.payload().orElseThrow());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void absentPayloadRoundTripsWithoutTrailingBytes() {
        Identifier id = Identifier.of("warland", "auth/cancel");
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            CustomClickActionC2SPacket.CODEC.encode(buffer, new CustomClickActionC2SPacket(id, Optional.empty()));
            var decoded = CustomClickActionC2SPacket.CODEC.decode(buffer);
            assertEquals(id, decoded.id());
            assertTrue(decoded.payload().isEmpty());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
}
