package ru.warland.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-boundary regressions; actual Fabric packet timing needs the separate runtime probe. */
class OfflineLoginSerializationTest {
    private static String mixin() throws Exception {
        return Files.readString(Path.of("src/main/java/ru/warland/mixin/OfflineLoginMixin.java"));
    }
    private static String late() throws Exception {
        String source = mixin();
        int start = source.indexOf("@Inject(method=\"tickVerify\"");
        assertTrue(start >= 0, "late tickVerify injection is required independently of onKey");
        return source.substring(start);
    }
    @Test void interceptsTickVerifyBeforeVanillaCanEvictDuplicate() throws Exception {
        String source = late();
        assertTrue(source.contains("at=@At(\"HEAD\"),cancellable=true"));
        assertTrue(source.contains("warland$rejectLateDuplicate(GameProfile profile,CallbackInfo ci)"));
        assertFalse(source.contains("disconnectDuplicateLogins("));
    }
    @Test void onlineModeIsUntouchedAndOfflineRequiresEncryptedUnoccupiedIdentity() throws Exception {
        String source = late();
        assertTrue(source.contains("if(!OfflineTransport.enabled(server))return;"));
        assertTrue(source.contains("!connection.isEncrypted()||server.getPlayerManager().getPlayer(profile.id())!=null"));
        assertTrue(source.indexOf("if(!OfflineTransport.enabled(server))return;") < source.indexOf("ci.cancel();"));
        assertFalse(source.contains("warland$offlineHandshake"), "onKey clears the handshake flag before this late phase");
    }
    @Test void rejectionCancelsVanillaAndDisconnectsOnlyTheNewHandler() throws Exception {
        String source = late();
        assertTrue(source.contains("ci.cancel();disconnect(Text.literal("));
        assertFalse(source.contains("getPlayer(profile.id()).networkHandler"));
        assertFalse(source.contains("startVerify("));
        assertFalse(source.contains("sendSuccessPacket("));
    }
    @Test void earlierGuardAndConfigurationReservationRemainIndependent() throws Exception {
        String source = mixin();
        assertTrue(source.contains("if(server.getPlayerManager().getPlayer(profile.id())!=null){disconnect("));
        assertTrue(source.contains("startVerify(profile);"));
        String runtime = Files.readString(Path.of("src/main/java/ru/warland/auth/AuthRuntime.java"));
        assertTrue(runtime.contains("engine.reserve(profile.id(), profile.name(), address.getAddress().getHostAddress())"));
        assertFalse(runtime.contains("engine.open("));
    }
}
