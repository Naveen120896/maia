package com.assistant.service;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VoiceInputService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Transcribes audio bytes (WAV/MP3/WEBM) to text using Groq Whisper.
     * @param audioBytes raw audio file bytes
     * @param filename   e.g. "audio.webm" or "audio.wav"
     * @return transcribed text
     */
    public String transcribe(byte[] audioBytes, String filename) {
        try {
            RequestBody fileBody = RequestBody.create(
                    audioBytes,
                    MediaType.parse("audio/*")
            );

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, fileBody)
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("response_format", "text")
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer " + groqApiKey)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                assert response.body() != null;
                String body = response.body().string();
                if (!response.isSuccessful()) {
                    System.out.println("❌ Whisper error: " + body);
                    return null;
                }
                // response_format=text returns plain string, not JSON
                return body.trim();
            }
        } catch (Exception e) {
            System.out.println("❌ Transcription failed: " + e.getMessage());
            return null;
        }
    }
}