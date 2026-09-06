package ru.warland.mixin;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ServerCommonNetworkHandler.class)
public interface AuthCommonAccess {
 @Accessor("connection") ClientConnection warland$connection();
 @Invoker("getProfile") GameProfile warland$profile();
}
