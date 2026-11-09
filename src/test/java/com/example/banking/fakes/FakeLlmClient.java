package com.example.banking.fakes;

import com.example.banking.ai.LlmClient;

/** Test double: returns a canned reply (or throws) instead of calling a real model. */
public class FakeLlmClient implements LlmClient {

    private String reply = "{}";
    private RuntimeException failure;
    private int calls;

    public FakeLlmClient replying(String reply) {
        this.reply = reply;
        this.failure = null;
        return this;
    }

    public FakeLlmClient failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        calls++;
        if (failure != null) {
            throw failure;
        }
        return reply;
    }

    public int calls() {
        return calls;
    }
}