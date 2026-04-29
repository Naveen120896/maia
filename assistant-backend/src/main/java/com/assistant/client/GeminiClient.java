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
public class GeminiClient {

    @Value("${gemini.api.key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // gemini-1.5-flash is the correct free-tier model name
    private static final String MODEL = "gemini-1.5-flash";

    public String generate(String prompt) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent?key=" + apiKey;

            // Use mapper — safe JSON, no manual escaping bugs
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", prompt)
                            ))
                    )
            ));

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body().string();
                System.out.println("🔥 GEMINI STATUS: " + response.code()
                        + " → " + body.substring(0, Math.min(200, body.length())));

                if (!response.isSuccessful()) {
                    throw new RuntimeException("Gemini HTTP " + response.code()
                            + " → " + body.substring(0, Math.min(200, body.length())));
                }

                JsonNode root = mapper.readTree(body);

                if (root.has("error")) {
                    throw new RuntimeException("Gemini error: "
                            + root.path("error").path("message").asText());
                }

                JsonNode candidates = root.path("candidates");
                if (!candidates.isArray() || candidates.isEmpty()) {
                    throw new RuntimeException("Gemini empty response");
                }

                return candidates.get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Gemini failed: " + e.getMessage());
        }
    }
}