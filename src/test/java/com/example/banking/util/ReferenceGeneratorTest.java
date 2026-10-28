package com.example.banking.util;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.model.AccountType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferenceGeneratorTest {

    private final ReferenceGenerator gen =
            new ReferenceGenerator(Clock.fixed(Instant.parse("2026-10-28T15:30:12Z"), ZoneOffset.UTC));

    @Test
    void accountNumberHasTypeDigitAndValidChecksum() {
        String number = gen.newAccountNumber(AccountType.LOAN);
        assertEquals(12, number.length());
        assertTrue(number.startsWith("104"));
        assertTrue(ReferenceGenerator.isValidAccountNumber(number));
    }

    @Test
    void anySingleMistypedDigitIsDetected() {
        String number = gen.newAccountNumber(AccountType.SAVINGS);
        for (int i = 0; i < number.length(); i++) {
            char[] typo = number.toCharArray();
            typo[i] = (char) ('0' + (typo[i] - '0' + 1) % 10);   // change one digit
            assertFalse(ReferenceGenerator.isValidAccountNumber(new String(typo)), "typo at position " + i);
        }
    }

    @Test
    void malformedNumbersAreInvalid() {
        assertFalse(ReferenceGenerator.isValidAccountNumber(null));
        assertFalse(ReferenceGenerator.isValidAccountNumber("12345"));
        assertFalse(ReferenceGenerator.isValidAccountNumber("10123456789X"));
    }

    @Test
    void transactionReferencesHaveTimestampAndAreUnique() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String ref = gen.newTransactionReference();
            assertTrue(ref.startsWith("TXN-20261028153012-"));
            refs.add(ref);
        }
        assertEquals(1000, refs.size());
    }
}