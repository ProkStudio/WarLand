package ru.warland.mixin;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientCommonNetworkHandler.class)
public interface AuthClientConnectionAccess {
 @Accessor("connection") ClientConnection warland$connection();
}
