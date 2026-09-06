package ru.warland.auth;
import java.util.Locale;
import java.util.Set;
/** Pure transport, command and operator-context policy. */
public final class RuntimePolicy {
 private static final Set<String> SECRET_ROOTS = Set.of("login", "l", "register", "reg", "changepassword", "changepass", "password", "resetpassword");
 private RuntimePolicy() {}
 public static boolean secure(boolean onlineMode, boolean encrypted) { return onlineMode && encrypted; }
 public static boolean credentialCommand(String command) {
  if (command == null || command.length() > 256) return true;
  var words = new java.util.StringTokenizer(command.toLowerCase(Locale.ROOT));
  while (words.hasMoreTokens()) {
   String word = words.nextToken(); while (word.startsWith("/")) word = word.substring(1);
   int colon = word.lastIndexOf(':'); if (SECRET_ROOTS.contains(word.substring(colon + 1))) return true;
  }
  return false;
 }
 public static boolean console(Object output, Object server, boolean hasEntity) { return server != null && output == server && !hasEntity; }
 /** Automatic/scheduled function sources are not an interactive owner console. */
 public static boolean provisioningContext(boolean silent, boolean ownerPermissions) { return !silent && ownerPermissions; }
}
