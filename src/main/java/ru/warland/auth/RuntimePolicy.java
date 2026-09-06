package ru.warland.auth;

import java.util.Locale;
import java.util.Set;

/** Pure policy used by both packet paths. Credentials are never command arguments. */
public final class RuntimePolicy {
    private static final Set<String> SECRET_ROOTS = Set.of("login", "l", "register", "reg", "changepassword", "changepass", "password", "resetpassword");
    private RuntimePolicy() {}
    public static boolean secure(boolean onlineMode, boolean encrypted) { return onlineMode && encrypted; }
    public static boolean credentialCommand(String command) {
        if (command == null || command.length() > 256) return true;
        // Also catch nested /execute ... run login and namespace/alias variants. No parser echoes input.
        for (String word : command.toLowerCase(Locale.ROOT).split("\s+")) {
            while (word.startsWith("/")) word = word.substring(1);
            int colon = word.lastIndexOf(':');
            if (SECRET_ROOTS.contains(word.substring(colon + 1))) return true;
        }
        return false;
    }
    public static boolean console(Object output, Object server, boolean hasEntity) {
        return server != null && output == server && !hasEntity;
    }
}
