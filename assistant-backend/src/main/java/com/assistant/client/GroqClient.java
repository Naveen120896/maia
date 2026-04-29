package com.assistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class GroqClient {

    @Value("${groq.api.key:}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // llama3-8b-8192 is decommissioned — use llama-3.1-8b-instant
    private static final String MODEL = "llama-3.1-8b-instant";

    public String generate(String prompt) {
        try {
            if (apiKey == null || apiKey.isBlank()) {
                throw new RuntimeException("Groq key missing in application.properties");
            }

            String jsonBody = mapper.writeValueAsString(Map.of(
                    "model", MODEL,
                    "max_tokens", 1024,
                    "messages", List.of(
                            Map.of("role", "system",
                                    "content", "You are a personal AI assistant with long-term memory."),
                            Map.of("role", "user", "content", prompt)
                    )
            ));

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body().string();
                System.out.println("🟣 GROQ STATUS: " + response.code()
                        + " → " + body.substring(0, Math.min(200, body.length())));

                if (!response.isSuccessful()) {
                    throw new RuntimeException("Groq HTTP " + response.code()
                            + " → " + body.substring(0, Math.min(200, body.length())));
                }

                JsonNode root = mapper.readTree(body);
                return root.path("choices").get(0)
                        .path("message").path("content").asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Groq failed: " + e.getMessage());
        }
    }
}