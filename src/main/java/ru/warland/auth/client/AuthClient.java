package ru.warland.auth.client;

import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import ru.warland.auth.AuthPayloads;
import ru.warland.mixin.AuthClientConnectionAccess;

/** No chat history, command completion, or password persistence. Dedicated-server companion UI. */
public final class AuthClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientConfigurationNetworking.registerGlobalReceiver(AuthPayloads.Challenge.ID, (challenge, context) -> context.client().execute(() -> {
            ClientConnection connection = ((AuthClientConnectionAccess) context.networkHandler()).warland$connection();
            if (!connection.isOpen() || !connection.isEncrypted() || connection.getPacketListener() != context.networkHandler()) return;
            Screen current = context.client().currentScreen;
            if (challenge.status() == 0) context.client().setScreen(new AuthScreen(current, connection, context.networkHandler(), context.responseSender(), challenge.nonce()));
            else if (current instanceof AuthScreen screen && screen.connection == connection && screen.nonce.equals(challenge.nonce())) {
                if (challenge.status() == 2) { screen.erase(); context.client().setScreen(screen.previous); }
                else if (challenge.status() == 1) screen.denied();
            }
        }));
    }
    private static final class AuthScreen extends Screen {
        private final Screen previous;
        private final ClientConnection connection;
        private final ClientConfigurationNetworkHandler handler;
        private final PacketSender sender;
        private final UUID nonce;
        private SecretField password, confirmation, token;
        private ButtonWidget login, register;
        private String status = "Пароль: 12–128 символов. Код нужен только владельцу.";
        private boolean busy;
        AuthScreen(Screen previous, ClientConnection connection, ClientConfigurationNetworkHandler handler, PacketSender sender, UUID nonce) {
            super(Text.literal("WarLand — защищённый вход"));
            this.previous = previous; this.connection = connection; this.handler = handler; this.sender = sender; this.nonce = nonce;
        }
        @Override protected void init() {
            int x = width / 2 - 150, y = height / 2 - 60;
            password = addDrawableChild(new SecretField(x, y, "Пароль", 128));
            confirmation = addDrawableChild(new SecretField(x, y + 28, "Повтор для регистрации", 128));
            token = addDrawableChild(new SecretField(x, y + 56, "Приватный код владельца", 43));
            login = addDrawableChild(ButtonWidget.builder(Text.literal("Войти"), b -> submit(false)).dimensions(x, y + 88, 145, 20).build());
            register = addDrawableChild(ButtonWidget.builder(Text.literal("Регистрация"), b -> submit(true)).dimensions(x + 155, y + 88, 145, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Отключиться"), b -> close()).dimensions(x, y + 116, 300, 20).build());
            login.active = register.active = !busy;
        }
        private void submit(boolean registration) {
            if (busy || !connection.isOpen() || !connection.isEncrypted() || connection.getPacketListener() != handler) { close(); return; }
            var request = new AuthPayloads.Request(nonce, registration, password.getText().toCharArray(),
                registration ? confirmation.getText().toCharArray() : new char[0], registration ? token.getText().toCharArray() : new char[0]);
            if (!request.valid()) { request.close(); status = "Проверьте длину и совпадение паролей."; return; }
            erase(); busy = true; login.active = register.active = false; status = "Проверка…";
            try { sender.sendPacket(request); }
            catch (RuntimeException unavailable) { request.close(); close(); }
        }
        private void denied() { erase(); busy = false; login.active = register.active = true; status = "Вход отклонён. Проверьте данные или подождите."; }
        private void erase() { if (password != null) { password.setText(""); confirmation.setText(""); token.setText(""); } }
        @Override public void removed() { erase(); }
        @Override public void close() { erase(); connection.disconnect(Text.literal("Авторизация отменена")); if(client != null) client.setScreen(previous); }
        @Override public boolean shouldCloseOnEsc() { return false; }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 94, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, height / 2 - 78, 0xFFB0B0B0);
        }
        private final class SecretField extends TextFieldWidget {
            private final String label;
            SecretField(int x, int y, String label, int limit) {
                super(AuthScreen.this.textRenderer, x, y, 300, 20, Text.literal(label)); this.label = label;
                // A paste exceeding the actual limit is rejected, never truncated into a valid password.
                setMaxLength(limit + 1); setTextPredicate(value -> value.length() <= limit);
                setPlaceholder(Text.literal(label));
                addFormatter((value, index) -> Text.literal("•".repeat(value.length())).asOrderedText());
            }
            @Override public String getSelectedText() { return ""; } // never export secrets with copy/cut
            @Override protected MutableText getNarrationMessage() { return Text.literal(label + ": скрыто"); }
        }
    }
}
