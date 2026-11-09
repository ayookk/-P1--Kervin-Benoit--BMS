package com.example.banking.ai;

/** Anything that can answer a chat prompt. An interface, so tests can use a fake instead of a real model. */
public interface LlmClient {

    /** Returns the model's raw reply text. Throws AssistantUnavailableException if the model can't be reached. */
    String chat(String systemPrompt, String userMessage);
}