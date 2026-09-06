package ru.warland.auth;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.dialog.*;
import net.minecraft.dialog.action.DynamicCustomDialogAction;
import net.minecraft.dialog.body.PlainMessageDialogBody;
import net.minecraft.dialog.input.TextInputControl;
import net.minecraft.dialog.type.*;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.network.packet.s2c.common.ShowDialogS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Vanilla CONFIGURATION UI: fixed actions, bounded fields, no chat/command execution. */
public final class NativeAuthDialog {
    public static final Identifier LOGIN = Identifier.of("warland", "auth/login");
    public static final Identifier REGISTER = Identifier.of("warland", "auth/register");
    public static final Identifier CANCEL = Identifier.of("warland", "auth/cancel");
    private static final Set<Identifier> ACTIONS = Set.of(LOGIN, REGISTER, CANCEL);
    private static final Set<String> FIELDS = Set.of("nonce", "password", "confirmation", "owner");
    private NativeAuthDialog() {}
    public static boolean enabled() { return Boolean.getBoolean("warland.vanillaClient"); }

    /** Only public input-shape feedback. Never expose account existence or password validity. */
    public enum Feedback {
        WELCOME("Первый вход: придумайте пароль и нажмите «Регистрация». Затем используйте «Войти»."),
        DENIED("Вход отклонён. Проверьте данные; после нескольких попыток подождите минуту и подключитесь заново."),
        PASSWORD_LENGTH("Пароль должен содержать от 12 до 128 символов. Это ваш новый пароль WarLand, не код владельца."),
        CONFIRMATION("Пароли в двух полях не совпадают. Для регистрации введите один и тот же пароль дважды."),
        OWNER_FORMAT("Код владельца: ровно 43 символа без пробелов. Остальным игрокам это поле нужно оставить пустым.");
        private final String message;
        Feedback(String message) { this.message = message; }
        public String message() { return message; }
    }
    public static Feedback inputFeedback(CustomClickActionC2SPacket packet) {
        if (!bounded(packet)) return Feedback.DENIED;
        if (CANCEL.equals(packet.id())) return null;
        var n = (NbtCompound) packet.payload().orElseThrow();
        String password = n.getString("password").orElse("");
        if (password.length() < 12 || password.length() > 128) return Feedback.PASSWORD_LENGTH;
        if (REGISTER.equals(packet.id())) {
            if (!password.equals(n.getString("confirmation").orElse(""))) return Feedback.CONFIRMATION;
            String owner = n.getString("owner").orElse("");
            if (!owner.isEmpty() && !owner.matches("[A-Za-z0-9_-]{43}")) return Feedback.OWNER_FORMAT;
        }
        return null;
    }
    public static ShowDialogS2CPacket show(UUID nonce, boolean denied) {
        return show(nonce, denied ? Feedback.DENIED : Feedback.WELCOME);
    }
    public static ShowDialogS2CPacket show(UUID nonce, Feedback feedback) {
        var common = new DialogCommonData(Text.literal("WarLand — вход"), Optional.empty(), false, false,
            // NONE keeps the real dialog mounted so ClearDialog also closes it.
            // A dropped/stale request must not trap vanilla on WaitingForResponseScreen.
            AfterAction.NONE,
            List.of(new PlainMessageDialogBody(Text.literal(feedback.message()), 300),
                new PlainMessageDialogBody(Text.literal("Окно входа действует 2 минуты. Если время истекло, подключитесь заново. После отказа заполните очищенные поля повторно."), 300),
                new PlainMessageDialogBody(Text.literal("Пароль 12–128 символов. Ввод виден на экране: не показывайте его посторонним. Используйте отдельный пароль только для WarLand."), 300)),
            List.of(field("password", "Пароль", 128), field("confirmation", "Повтор — для регистрации", 128),
                field("owner", "Одноразовый код владельца — не пароль", 43)));
        return new ShowDialogS2CPacket(RegistryEntry.of(new MultiActionDialog(common,
            List.of(button("Войти", LOGIN, nonce), button("Регистрация", REGISTER, nonce)),
            Optional.of(button("Отключиться", CANCEL, nonce)), 2)));
    }
    private static DialogInput field(String key, String label, int max) {
        return new DialogInput(key, new TextInputControl(300, Text.literal(label), true, "", max, Optional.empty()));
    }
    private static DialogActionButtonData button(String label, Identifier id, UUID nonce) {
        var additions = new NbtCompound(); additions.putString("nonce", nonce.toString());
        return new DialogActionButtonData(new DialogButtonData(Text.literal(label), 145),
            Optional.of(new DynamicCustomDialogAction(id, Optional.of(additions))));
    }
    public static boolean bounded(CustomClickActionC2SPacket packet) {
        if (packet == null || packet.id() == null || !ACTIONS.contains(packet.id())
                || !(packet.payload().orElse(null) instanceof NbtCompound n) || !FIELDS.equals(n.getKeys())) return false;
        for (String key : FIELDS) {
            if (!(n.get(key) instanceof NbtString)) return false;
            String value = n.getString(key).orElse("");
            int max = key.equals("nonce") ? 36 : key.equals("owner") ? 43 : 128;
            if (value.length() > max) return false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isISOControl(c)) return false;
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
                } else if (Character.isLowSurrogate(c)) return false;
            }
        }
        return true;
    }
    public static boolean nonceMatches(CustomClickActionC2SPacket packet, UUID expected) {
        return expected != null && bounded(packet) && expected.toString().equals(
            ((NbtCompound) packet.payload().orElseThrow()).getString("nonce").orElse(""));
    }
    public static AuthPayloads.Request decode(CustomClickActionC2SPacket packet, UUID expected) {
        if (!nonceMatches(packet, expected) || CANCEL.equals(packet.id()))
            throw new IllegalArgumentException("Invalid authentication action");
        var n = (NbtCompound) packet.payload().orElseThrow();
        boolean registration = REGISTER.equals(packet.id());
        // Login never enrolls an owner, regardless of unused registration fields.
        return new AuthPayloads.Request(expected, registration, n.getString("password").orElse("").toCharArray(),
            registration ? n.getString("confirmation").orElse("").toCharArray() : new char[0],
            registration ? n.getString("owner").orElse("").toCharArray() : new char[0]);
    }
}
