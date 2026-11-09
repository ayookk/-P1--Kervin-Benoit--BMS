package com.example.banking.ai;

import com.example.banking.exception.AssistantUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to a LOCAL Ollama server with Java's built-in HttpClient (no web framework).
 * The model runs on this computer, so customer messages never leave the machine.
 */
public class OllamaClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI chatUri;
    private final String model;
    private final Duration timeout;

    public OllamaClient(String baseUrl, String model, Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("format", "json");                          // Ollama only returns valid JSON
        body.put("keep_alive", "30m");                       // keep the model loaded between requests
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);                       // same question -> same answer, as far as possible
        options.put("num_predict", 120);                     // the answer is one short JSON line
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userMessage);

        try {
            HttpRequest request = HttpRequest.newBuilder(chatUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssistantUnavailableException(
                        new IOException("Ollama returned HTTP " + response.statusCode()));
            }
            JsonNode content = json.readTree(response.body()).path("message").path("content");
            if (!content.isTextual()) {
                throw new AssistantUnavailableException(new IOException("Unexpected Ollama response"));
            }
            return content.asText();
        } catch (IOException e) {                            // not running, timed out, unreadable reply
            throw new AssistantUnavailableException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantUnavailableException(e);
        }
    }
}