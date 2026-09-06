package ru.warland.auth;
/** Pure policy; requesting offline mode never removes the encryption requirement. */
public final class OfflinePolicy {
 private OfflinePolicy() {}
 public static boolean enabled(boolean online, boolean dedicated, boolean requested) {return !online&&dedicated&&requested;}
 public static boolean secure(boolean online, boolean encrypted, boolean dedicated, boolean requested) {return encrypted&&(online||enabled(online,dedicated,requested));}
}
