package com.example.banking.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.banking.ai.AssistantIntent.Transfer;
import com.example.banking.model.AccountType;
import com.example.banking.model.Money;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Talks to the REAL local Ollama. Skipped during a normal "mvn test" so results never depend on the model.
 * Run it on purpose with:  mvn test "-Dtest=OllamaLiveTest" "-Dollama.live=true"
 * Try another model with:  add "-Dollama.model=llama3.2"
 */
class OllamaLiveTest {

    @Test
    void modelUnderstandsATransfer() {
        assumeTrue(Boolean.getBoolean("ollama.live"), "add -Dollama.live=true to run against a real Ollama");
        String model = System.getProperty("ollama.model", "llama3");

        IntentParser parser = new IntentParser(
                new OllamaClient("http://localhost:11434", model, Duration.ofSeconds(180)));
        long start = System.nanoTime();
        AssistantIntent intent = parser.parse("please send 25 dollars from my savings to 102202600023");
        long seconds = (System.nanoTime() - start) / 1_000_000_000;
        System.out.println(">>> " + model + " answered in " + seconds + " s: " + intent);

        Transfer t = assertInstanceOf(Transfer.class, intent, () -> "model understood: " + intent);
        assertEquals(AccountType.SAVINGS, t.from());
        assertEquals("102202600023", t.toAccountNumber());
        assertEquals(Money.of("25.00"), t.amount());
    }
}