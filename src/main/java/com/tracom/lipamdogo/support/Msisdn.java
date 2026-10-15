package com.tracom.lipamdogo.support;

/**
 * Kenyan M-PESA numbers in the canonical 2547XXXXXXXX / 2541XXXXXXXX form.
 */
public final class Msisdn {

    private Msisdn() {
    }

    /** "0712 345 678", "+254712345678", "712345678" → "254712345678"; null when not a Kenyan mobile number. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("254") && digits.length() == 12) {
            return digits;
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        if (digits.length() == 9 && (digits.startsWith("7") || digits.startsWith("1"))) {
            return "254" + digits;
        }
        return null;
    }
}
