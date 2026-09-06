package ru.warland.auth;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class AuthWireTest {
 @Test void roundTripClearsSenderAndReceiverAndRedacts() {
  char[] secret="example test password".toCharArray();char[] confirmation=secret.clone();
  var request=new AuthPayloads.Request(UUID.randomUUID(),true,secret,confirmation,new char[0]);
  var buf=new PacketByteBuf(Unpooled.buffer());
  try {
   assertFalse(request.toString().contains("example"));
   AuthPayloads.Request.CODEC.encode(buf,request);assertEquals('\0',secret[0]);assertEquals('\0',confirmation[0]);
   var decoded=AuthPayloads.Request.CODEC.decode(buf);assertTrue(decoded.valid());assertEquals(request.nonce,decoded.nonce);
   decoded.close();assertEquals('\0',decoded.password()[0]);
  } finally {buf.release();}
 }
 @Test void malformedFieldsAndTrailingDataFailWithoutEcho() {
  var b=new PacketByteBuf(Unpooled.buffer());
  try {
   b.writeByte(1);b.writeUuid(UUID.randomUUID());b.writeByte(0);b.writeShort(129);
   assertThrows(RuntimeException.class,()->AuthPayloads.Request.CODEC.decode(b));
   b.clear();b.writeZero(641);assertThrows(RuntimeException.class,()->AuthPayloads.Request.CODEC.decode(b));
  } finally {b.release();}
 }
 @Test void confirmationCheckedBeforeKdfAndLoginRejectsExtraSecrets() {
  try(var p=new AuthPayloads.Request(UUID.randomUUID(),true,"long-password-a".toCharArray(),"long-password-b".toCharArray(),new char[0])){assertFalse(p.valid());}
  try(var p=new AuthPayloads.Request(UUID.randomUUID(),false,"long-password-a".toCharArray(),new char[0],new char[]{'x'})){assertFalse(p.valid());}
 }
}
