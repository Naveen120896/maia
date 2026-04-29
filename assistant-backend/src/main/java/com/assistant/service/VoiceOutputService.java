package com.assistant.service;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VoiceOutputService {

    @Value("${elevenlabs.api.key:}")
    private String elevenLabsKey;

    // Default ElevenLabs voice — "Rachel" (natural, friendly)
    private static final String VOICE_ID = "21m00Tcm4TlvDq8ikWAM";

    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Converts text to speech audio bytes (MP3).
     * Returns null if TTS is unavailable.
     */
    public byte[] synthesize(String text) {
        if (elevenLabsKey == null || elevenLabsKey.isBlank()) {
            System.out.println("⚠️ ElevenLabs key not set, skipping TTS");
            return null;
        }

        try {
            String json = String.format("""
                {
                  "text": "%s",
                  "model_id": "eleven_turbo_v2",
                  "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75
                  }
                }
                """, text.replace("\"", "\\\"").replace("\n", " "));

            Request request = new Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/" + VOICE_ID)
                    .addHeader("xi-api-key", elevenLabsKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.out.println("❌ ElevenLabs error: " + response.code());
                    return null;
                }
                assert response.body() != null;
                return response.body().bytes();
            }
        } catch (Exception e) {
            System.out.println("❌ TTS failed: " + e.getMessage());
            return null;
        }
    }
}