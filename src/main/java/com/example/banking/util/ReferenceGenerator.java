package com.example.banking.util;

import com.example.banking.model.AccountType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Produces account numbers and transaction references. Injected so tests can control it (doc section 32). */
public class ReferenceGenerator {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    // no 0/O or 1/I, so references are easy to read aloud
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    /** 12 digits: "10" + type digit + 8 random digits + Luhn check digit. */
    public String newAccountNumber(AccountType type) {
        StringBuilder sb = new StringBuilder("10").append(typeDigit(type));
        for (int i = 0; i < 8; i++) {
            sb.append(random.nextInt(10));
        }
        sb.append(luhnCheckDigit(sb));
        return sb.toString();
    }

    /** e.g. TXN-20261028153012-K7PQ2M (UTC time + 6 random characters). */
    public String newTransactionReference() {
        StringBuilder sb = new StringBuilder("TXN-").append(STAMP.format(clock.instant())).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(REF_CHARS.charAt(random.nextInt(REF_CHARS.length())));
        }
        return sb.toString();
    }

    /** True if the text is 12 digits and its last digit is the correct Luhn check digit. */
    public static boolean isValidAccountNumber(String number) {
        if (number == null || !number.matches("\\d{12}")) {
            return false;
        }
        return luhnCheckDigit(number.substring(0, 11)) == number.charAt(11) - '0';
    }

    static int typeDigit(AccountType type) {
        return switch (type) {
            case SAVINGS -> 1;
            case CURRENT -> 2;
            case DEPOSIT -> 3;
            case LOAN -> 4;
        };
    }

    /** Luhn algorithm: catches any single mistyped digit and most swapped neighbours. */
    static int luhnCheckDigit(CharSequence payload) {
        int sum = 0;
        boolean doubleIt = true;   // the digit next to the check digit is doubled first
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}