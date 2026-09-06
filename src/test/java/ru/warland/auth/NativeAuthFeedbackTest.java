package ru.warland.auth;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.dialog.AfterAction;
import net.minecraft.dialog.type.MultiActionDialog;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeAuthFeedbackTest {
 private static final UUID NONCE=UUID.fromString("90c45d9d-c4ab-43a7-8686-d39c48d758c5");
 private static final String PASSWORD="Synthetic-only-password-2026";
 private static CustomClickActionC2SPacket packet(Identifier action,String password,String confirmation,String owner){
  var n=new NbtCompound();n.putString("nonce",NONCE.toString());n.putString("password",password);n.putString("confirmation",confirmation);n.putString("owner",owner);
  return new CustomClickActionC2SPacket(action,Optional.of(n));
 }
 @Test void everyResponseKeepsTheRealDialogMounted(){
  for(var f:NativeAuthDialog.Feedback.values()){
   var d=(MultiActionDialog)NativeAuthDialog.show(NONCE,f).dialog().value();
   assertEquals(AfterAction.NONE,d.common().afterAction());assertFalse(d.common().canCloseWithEscape());assertFalse(d.common().pause());assertEquals(3,d.common().inputs().size());
  }
 }
 @Test void initialAndDenialOverloadsUseRecoverableScreen(){for(boolean denied:new boolean[]{false,true})assertEquals(AfterAction.NONE,((MultiActionDialog)NativeAuthDialog.show(NONCE,denied).dialog().value()).common().afterAction());}
 @Test void shortPasswordGivesActionableLocalFeedback(){assertEquals(NativeAuthDialog.Feedback.PASSWORD_LENGTH,NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.REGISTER,"short","short","")));}
 @Test void mismatchGivesActionableLocalFeedback(){assertEquals(NativeAuthDialog.Feedback.CONFIRMATION,NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.REGISTER,PASSWORD,"other-password-2026","")));}
 @Test void ownerProofShapeIsValidatedWithoutGrantingAnything(){for(String p:new String[]{"too-short","A".repeat(42)+" ","A".repeat(42)+"!"})assertEquals(NativeAuthDialog.Feedback.OWNER_FORMAT,NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.REGISTER,PASSWORD,PASSWORD,p)));}
 @Test void correctShapeOnlyPassesToExistingAuthentication(){for(String p:new String[]{"","A".repeat(43)})assertNull(NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.REGISTER,PASSWORD,PASSWORD,p)));}
 @Test void loginIgnoresUnusedRegistrationFields(){assertNull(NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.LOGIN,PASSWORD,"different","unused")));}
 @Test void cancelDoesNotRequireValidCredentials(){assertNull(NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.CANCEL,"","","")));}
 @Test void unknownAndMalformedRemainDenied(){
  assertEquals(NativeAuthDialog.Feedback.DENIED,NativeAuthDialog.inputFeedback(packet(Identifier.of("other","action"),PASSWORD,PASSWORD,"")));
  assertEquals(NativeAuthDialog.Feedback.DENIED,NativeAuthDialog.inputFeedback(null));
  assertEquals(NativeAuthDialog.Feedback.DENIED,NativeAuthDialog.inputFeedback(packet(NativeAuthDialog.REGISTER,"A".repeat(129),"A".repeat(129),"")));
 }
 @Test void messagesNeverReflectCredentials(){for(var f:NativeAuthDialog.Feedback.values()){assertFalse(f.message().contains(PASSWORD));assertFalse(f.message().contains("A".repeat(43)));}}
 @Test void timeoutUsesProtocolDisconnectAndPreservesSecurityGuards() throws Exception{
  String s=Files.readString(Path.of("src/main/java/ru/warland/auth/AuthRuntime.java"));
  assertTrue(s.contains("s.configuration.disconnect(s.released ? DENIED : TIMED_OUT)"));assertTrue(s.contains("NativeAuthDialog.inputFeedback(packet)"));assertTrue(s.contains("NativeAuthDialog.nonceMatches(packet, s.identity.nonce())"));assertTrue(s.contains("TimeUnit.SECONDS.toNanos(120)"));assertTrue(s.contains("!connection.isEncrypted()"));assertFalse(s.contains("entry.getKey().disconnect(DENIED)"));
 }
}
