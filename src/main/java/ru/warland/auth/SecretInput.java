package ru.warland.auth;

/** Validate a complete insertion before vanilla TextFieldWidget can clip it. */
public final class SecretInput {
    private SecretInput() {}
    public static boolean fits(int currentLength, int selectedLength, String insertion, int limit) {
        if (insertion == null || limit < 0 || currentLength < 0 || selectedLength < 0
                || selectedLength > currentLength || currentLength > limit
                || (long) currentLength - selectedLength + insertion.length() > limit) return false;
        for (int i = 0; i < insertion.length(); i++) {
            char c = insertion.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= insertion.length() || !Character.isLowSurrogate(insertion.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
