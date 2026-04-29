package com.assistant.controller;

import com.assistant.service.ChatService;
import com.assistant.service.VoiceInputService;
import com.assistant.service.VoiceOutputService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

@RestController
@RequestMapping("/api/voice")
public class VoiceChatController {

    private final VoiceInputService  voiceInput;
    private final VoiceOutputService voiceOutput;
    private final ChatService        chatService;

    public VoiceChatController(VoiceInputService voiceInput,
                               VoiceOutputService voiceOutput,
                               ChatService chatService) {
        this.voiceInput  = voiceInput;
        this.voiceOutput = voiceOutput;
        this.chatService = chatService;
    }

    /**
     * Safely encodes any Unicode string for use in an HTTP header.
     * HTTP headers only allow ISO-8859-1 (0–255), so we Base64-encode
     * anything that might contain Tamil, Arabic, Chinese, emoji, etc.
     */
    private String safeHeader(String value) {
        if (value == null) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * POST /api/voice/chat
     * Accepts audio file → transcribes → gets AI reply → returns MP3 audio
     *
     * Headers returned:
     *   X-Transcript-B64 — Base64(UTF-8) encoded transcript
     *   X-AI-Text-B64    — Base64(UTF-8) encoded AI reply text
     *   X-Fallback       — "true" if TTS was unavailable
     *
     * Use ?textOnly=true to skip TTS and just get the text reply (for testing)
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> voiceChat(
            @RequestParam("audio")                  MultipartFile audio,
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "false")   boolean textOnly
    ) {
        try {
            // ── Step 1: Transcribe audio → text ──
            String transcript = voiceInput.transcribe(
                    audio.getBytes(),
                    audio.getOriginalFilename() != null
                            ? audio.getOriginalFilename() : "audio.webm"
            );

            if (transcript == null || transcript.isBlank()) {
                return ResponseEntity.badRequest()
                        .body("❌ Could not transcribe audio. Please try again.");
            }

            System.out.println("🎤 Transcript: " + transcript);

            // ── Step 2: Get AI response ──
            String aiText = chatService.chat(userId, transcript);

            // ── Step 3: Return text only (debug/fallback) ──
            if (textOnly) {
                return ResponseEntity.ok()
                        .header("X-Transcript-B64", safeHeader(transcript))
                        .body(aiText);
            }

            // ── Step 4: Synthesize AI response → audio ──
            byte[] audioBytes = voiceOutput.synthesize(aiText);

            if (audioBytes == null) {
                // TTS unavailable — return text with safe Base64 headers
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Transcript-B64", safeHeader(transcript))
                        .header("X-AI-Text-B64",    safeHeader(aiText))
                        .header("X-Fallback",        "true")
                        .body(aiText);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=response.mp3")
                    .header("X-Transcript-B64", safeHeader(transcript))
                    .header("X-AI-Text-B64",    safeHeader(aiText))
                    .body(audioBytes);

        } catch (Exception e) {
            System.out.println("❌ Voice chat error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body("Voice chat failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/voice/transcribe
     * Only transcribes audio → returns text (no AI call)
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> transcribeOnly(@RequestParam("audio") MultipartFile audio) {
        try {
            String transcript = voiceInput.transcribe(
                    audio.getBytes(),
                    audio.getOriginalFilename() != null
                            ? audio.getOriginalFilename() : "audio.webm"
            );
            return transcript != null
                    ? ResponseEntity.ok(transcript)
                    : ResponseEntity.badRequest().body("Transcription failed");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * POST /api/voice/speak
     * Converts any text to audio (useful for replaying responses)
     */
    @PostMapping("/speak")
    public ResponseEntity<?> speak(@RequestBody String text) {
        byte[] audio = voiceOutput.synthesize(text);
        if (audio == null) {
            return ResponseEntity.badRequest().body("TTS unavailable");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }
}