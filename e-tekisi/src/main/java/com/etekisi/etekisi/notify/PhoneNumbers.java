package com.etekisi.etekisi.notify;

/** Phone numbers as people type them, to E.164 for SMS providers. South Africa is the default country. */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** "082 123 4567" or "+27 82 123 4567" -> "+27821234567"; null if it can't be an SA/international mobile. */
    public static String toE164(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            String rest = digits.substring(1).replace("+", "");
            return rest.length() >= 8 && rest.length() <= 15 ? "+" + rest : null;
        }
        if (digits.startsWith("00")) return toE164("+" + digits.substring(2));
        if (digits.startsWith("0") && digits.length() == 10) return "+27" + digits.substring(1);
        if (digits.startsWith("27") && digits.length() == 11) return "+" + digits;
        return null;
    }

    /** For logs: "+27******4567". */
    public static String masked(String e164) {
        if (e164 == null || e164.length() < 7) return "***";
        return e164.substring(0, 3) + "*".repeat(e164.length() - 7) + e164.substring(e164.length() - 4);
    }
}
