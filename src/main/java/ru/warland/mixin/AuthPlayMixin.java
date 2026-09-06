package ru.warland.mixin;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.RuntimePolicy;
import ru.warland.core.CoreRuntime;
@Mixin(value=ServerPlayNetworkHandler.class, priority=3000)
public abstract class AuthPlayMixin {
 @Shadow public ServerPlayerEntity player;
 @Inject(method={"onClientCommand", "onPlayerInteractBlock", "onRecipeBookData", "onChatMessage", "onUpdateCommandBlockMinecart", "onTeleportConfirm", "onUpdateStructureBlock", "onHandSwing", "onBookUpdate", "onCloseHandledScreen", "onButtonClick", "onUpdateSelectedSlot", "onUpdateBeacon", "onAdvancementTab", "onRequestCommandCompletions", "onRenameItem", "onCraftRequest", "onPlayerInteractEntity", "onPlayerMove", "onBoatPaddleState", "onPlayerInteractItem", "onPlayerAction", "onPlayerInput", "onClientStatus", "onCreativeInventoryAction", "onUpdateSign", "onQueryBlockNbt", "onSpectatorTeleport", "onQueryEntityNbt", "onClickSlot", "onUpdateCommandBlock", "onVehicleMove", "onSelectMerchantTrade", "onUpdatePlayerAbilities", "onPickItemFromEntity", "onUpdateJigsaw", "onUpdateDifficulty", "onUpdateDifficultyLock", "onJigsawGenerating", "onRecipeCategoryOptions", "onCommandExecution", "onMessageAcknowledgment", "onPlayerSession", "onAcknowledgeChunks", "onAcknowledgeReconfiguration", "onSlotChangedState", "onDebugSubscriptionRequest", "onChatCommandSigned", "onClientTickEnd", "onBundleItemSelected", "onPickItemFromBlock", "onPlayerLoaded", "onSetTestBlock", "onTestInstanceBlockAction", "onChangeGameMode", "tick", "addBook", "updateBookContent", "onSignUpdate", "handleDecoratedMessage", "handleCommandExecution", "executeCommand"}, at=@At("HEAD"), cancellable=true)
 private void warland$admission(CallbackInfo ci) {
  CoreRuntime r=CoreRuntime.INSTANCE;
  if(r!=null&&!r.authorized(player))ci.cancel();
 }
 @Inject(method="onCommandExecution", at=@At("HEAD"), cancellable=true)
 private void warland$unsigned(CommandExecutionC2SPacket packet, CallbackInfo ci) {
  if(RuntimePolicy.credentialCommand(packet.command()))ci.cancel();
 }
 @Inject(method="onChatCommandSigned", at=@At("HEAD"), cancellable=true)
 private void warland$signed(ChatCommandSignedC2SPacket packet, CallbackInfo ci) {
  if(RuntimePolicy.credentialCommand(packet.command()))ci.cancel();
 }
}
