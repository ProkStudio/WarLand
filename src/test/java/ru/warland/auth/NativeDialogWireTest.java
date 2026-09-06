package ru.warland.auth;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeDialogWireTest {
 @Test void nativePayloadIsLengthPrefixedNotPresenceByte(){
  var tag=new NbtCompound();tag.putString("nonce","synthetic");
  var packet=new CustomClickActionC2SPacket(NativeAuthDialog.LOGIN,Optional.of(tag));
  var buffer=new PacketByteBuf(Unpooled.buffer());
  try{
   CustomClickActionC2SPacket.CODEC.encode(buffer,packet);
   assertEquals(NativeAuthDialog.LOGIN,buffer.readIdentifier());
   int length=buffer.readVarInt();assertEquals(length,buffer.readableBytes());assertEquals(10,buffer.getUnsignedByte(buffer.readerIndex()));
   buffer.readerIndex(0);assertEquals(packet,CustomClickActionC2SPacket.CODEC.decode(buffer));assertFalse(buffer.isReadable());
  }finally{buffer.release();}
 }
 @Test void emptyNativePayloadRoundTrips(){
  var packet=new CustomClickActionC2SPacket(NativeAuthDialog.CANCEL,Optional.empty());
  var buffer=new PacketByteBuf(Unpooled.buffer());
  try{CustomClickActionC2SPacket.CODEC.encode(buffer,packet);assertEquals(packet,CustomClickActionC2SPacket.CODEC.decode(buffer));assertFalse(buffer.isReadable());}
  finally{buffer.release();}
 }
}
