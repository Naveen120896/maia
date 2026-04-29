package com.assistant.service;

import com.assistant.client.GeminiClient;
import com.assistant.client.GroqClient;
import com.assistant.entity.ChatCache;
import com.assistant.entity.ChatHistory;
import com.assistant.entity.UserMemory;
import com.assistant.entity.UserSummary;
import com.assistant.repository.ChatCacheRepository;
import com.assistant.repository.ChatHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class ChatService {

    private final ChatHistoryRepository chatRepo;
    private final ChatCacheRepository   cacheRepo;
    private final MemoryService         memoryService;
    private final GroqClient            groqClient;
    private final GeminiClient          geminiClient;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper mapper     = new ObjectMapper();

    @Value("${openai.api.key:}")
    private String openaiKey;

    public ChatService(ChatHistoryRepository chatRepo,
                       ChatCacheRepository   cacheRepo,
                       MemoryService         memoryService,
                       GroqClient            groqClient,
                       GeminiClient          geminiClient) {
        this.chatRepo      = chatRepo;
        this.cacheRepo     = cacheRepo;
        this.memoryService = memoryService;
        this.groqClient    = groqClient;
        this.geminiClient  = geminiClient;
    }

    // =========================================================
    // MAIN ENTRY POINT
    // =========================================================
    @Transactional
    public String chat(String userId, String message) {

        if (userId == null || userId.isBlank()) userId = "default";
        message = message.trim();

        // ── CLEAR LOGS ──
        if ("clear logs".equalsIgnoreCase(message)) {
            chatRepo.deleteByUserId(userId);
            cacheRepo.deleteByUserId(userId);
            memoryService.clearMemory(userId);
            return "🧹 Chat, Cache + Memory cleared!";
        }

        // ── SHOW MEMORY ──
        if ("show memory".equalsIgnoreCase(message)) {
            return memoryService.formatMemoryDump(userId);
        }

        boolean isDocRequest = message.startsWith("DOCUMENT_IMPROVE_REQUEST") ||
                message.startsWith("DOCUMENT_QUESTION_REQUEST");

        // ── SAVE USER MESSAGE + ALWAYS EXTRACT FACTS ──
        if (!isDocRequest) {
            saveSafe(userId, "USER", message);
            // No keyword matching — LLM decides what's personal, returns {} if nothing found
            memoryService.extractAndSave(userId, message);
        }

        // ── CACHE CHECK (skip for personal questions and doc requests) ──
        if (!isDocRequest && !isPersonalQuestion(message)) {
            String hash = generateHash(message);
            Optional<ChatCache> cached = cacheRepo.findByQuestionHash(hash);
            if (cached.isPresent()) {
                System.out.println("⚡ Cache hit for: " + message);
                return cached.get().getResponse() + " ⚡(cached)";
            }
        }

        // ── LOAD FULL CONTEXT ──
        Optional<UserSummary> summary = memoryService.getSummary(userId);
        List<UserMemory>      memory  = memoryService.getMemories(userId);
        List<ChatHistory>     history = isDocRequest
                ? List.of()
                : chatRepo.findTop20ByUserIdOrderByCreatedAtDesc(userId);

        // ── BUILD PROMPT ──
        String prompt = buildPrompt(summary, memory, history, message, isDocRequest);

        // ── CALL AI — Groq → Gemini → OpenAI ──
        String aiResponse = callGroq(prompt);

        if (isErrorResponse(aiResponse)) {
            System.out.println("⚠️ Groq failed → trying Gemini");
            aiResponse = callGemini(prompt);
        }

        if (isErrorResponse(aiResponse)) {
            System.out.println("⚠️ Gemini failed → trying OpenAI");
            aiResponse = callOpenAI(prompt);
        }

        if (isErrorResponse(aiResponse)) {
            return "⚠️ All AI providers busy. Try again later.";
        }

        // ── SAVE CACHE (non-personal, non-doc only) ──
        if (!isDocRequest && !isPersonalQuestion(message)) {
            try {
                String hash = generateHash(message);
                ChatCache cache = new ChatCache();
                cache.setUserId(userId);
                cache.setQuestionHash(hash);
                cache.setResponse(aiResponse);
                cacheRepo.save(cache);
            } catch (Exception e) {
                System.out.println("❌ CACHE SAVE ERROR: " + e.getMessage());
            }
        }

        // ── SAVE ASSISTANT RESPONSE ──
        saveSafe(userId, "ASSISTANT", aiResponse);

        return aiResponse;
    }

    // =========================================================
    // PROMPT BUILDER
    // =========================================================
    private String buildPrompt(Optional<UserSummary> summary,
                               List<UserMemory>      memory,
                               List<ChatHistory>     history,
                               String                message,
                               boolean               isDocRequest) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a personal AI assistant with long-term memory.\n");
        sb.append("You ONLY know what is explicitly stored in 'Key facts' and 'About this user' sections below.\n");
        sb.append("STRICT RULES — you MUST follow these always:\n");
        sb.append("  1. NEVER invent, guess, or assume any fact about the user.\n");
        sb.append("  2. NEVER use information from chat history to make assumptions about the user.\n");
        sb.append("  3. If a fact is not in 'Key facts', say 'I don't have that information yet — please tell me.'\n");
        sb.append("  4. NEVER say things like 'I recall you enjoy hiking' or 'I remember you mentioned' unless it is in Key facts.\n");
        sb.append("  5. Use ONLY stored facts. Be honest. Be genuine. No hallucination.\n");
        if (isDocRequest && message.contains("DOCUMENT TO IMPROVE")) {
            sb.append("When asked to improve a document, output the complete rewritten document in full. ");
            sb.append("Never truncate, summarise, or refuse. Output only the improved document text.\n");
        } else {
            sb.append("Be concise, warm, and personal — like a trusted friend who knows the user well.\n");
            sb.append("CRITICAL: If the user asks about themselves or their family/life, ");
            sb.append("answer using the facts in 'Key facts' and 'About this user' below. ");
            sb.append("Never say you don't know something that is already listed in those sections.\n");
        }

        sb.append("IMPORTANT: Never claim to have uploaded, saved, sent, or stored any file anywhere. ");
        sb.append("You can only read and respond to document content shown directly in this prompt.\n\n");

        if (!isDocRequest) {
            if (summary.isPresent() && !summary.get().getSummary().isBlank()) {
                sb.append("=== About this user ===\n");
                sb.append(summary.get().getSummary()).append("\n\n");
            }

            if (!memory.isEmpty()) {
                sb.append("=== Key facts ===\n");
                for (UserMemory m : memory) {
                    sb.append("• ").append(m.getMemoryKey())
                            .append(": ").append(m.getMemoryValue()).append("\n");
                }
                sb.append("\n");
            }

            if (!history.isEmpty()) {
                sb.append("=== Recent conversation ===\n");
                for (int i = history.size() - 1; i >= 0; i--) {
                    ChatHistory h = history.get(i);
                    sb.append(h.getRole()).append(": ").append(h.getMessage()).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("User: ").append(message);
        return sb.toString();
    }

    // =========================================================
    // PRIMARY — GROQ
    // =========================================================
    private String callGroq(String prompt) {
        try {
            System.out.println("🟣 Calling Groq...");
            String response = groqClient.generate(prompt);
            System.out.println("🟣 Groq responded OK");
            return response;
        } catch (Exception e) {
            System.out.println("❌ Groq failed: " + e.getMessage());
            return "ERROR";
        }
    }

    // =========================================================
    // FALLBACK — GEMINI
    // =========================================================
    private String callGemini(String prompt) {
        try {
            System.out.println("🔵 Calling Gemini...");
            String response = geminiClient.generate(prompt);
            System.out.println("🔵 Gemini responded OK");
            return response;
        } catch (Exception e) {
            System.out.println("❌ Gemini failed: " + e.getMessage());
            return "ERROR";
        }
    }

    // =========================================================
    // LAST RESORT — OPENAI
    // =========================================================
    private String callOpenAI(String prompt) {
        try {
            if (openaiKey == null || openaiKey.isBlank()) {
                System.out.println("⚠️ OpenAI key not configured");
                return "ERROR";
            }

            // Safe JSON build using ObjectMapper — no manual escaping bugs
            ObjectNode body = mapper.createObjectNode();
            body.put("model", "gpt-4o-mini");
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", "You are a helpful personal assistant.");
            messages.addObject().put("role", "user").put("content", prompt);
            String jsonBody = mapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + openaiKey)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    System.out.println("❌ OpenAI: empty response body");
                    return "ERROR";
                }

                String responseStr = responseBody.string();
                System.out.println("🟡 OPENAI STATUS: " + response.code());

                if (!response.isSuccessful()) {
                    System.out.println("❌ OpenAI error: " + responseStr);
                    return "ERROR";
                }

                JsonNode root = mapper.readTree(responseStr);
                return root.path("choices").get(0)
                        .path("message").path("content").asText();
            }
        } catch (Exception e) {
            System.out.println("❌ OpenAI failed: " + e.getMessage());
            return "ERROR";
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================
    private void saveSafe(String userId, String role, String message) {
        try {
            ChatHistory chat = new ChatHistory();
            chat.setUserId(userId);
            chat.setRole(role);
            chat.setMessage(message);
            chatRepo.save(chat);
        } catch (Exception e) {
            System.out.println("❌ DB SAVE ERROR: " + e.getMessage());
        }
    }

    private String generateHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    // =========================================================
    // Detects messages that ASK about stored personal info.
    // Skips cache so live memory is always used for answers.
    // =========================================================
    private boolean isPersonalQuestion(String message) {
        String m = message.toLowerCase();
        return m.contains("do you know")      || m.contains("do you remember")  ||
                m.contains("what do you know") || m.contains("tell me about me") ||
                m.contains("who am i")         || m.contains("about me")         ||
                m.contains("remember me")      || m.contains("my profile")       ||
                m.contains("show my")          || m.contains("what is my")       ||
                m.contains("what's my")        || m.contains("where do i")       ||
                m.contains("my name")          || m.contains("my age")           ||
                m.contains("my city")          || m.contains("my job")           ||
                m.contains("my company")       || m.contains("my college")       ||
                m.contains("my degree")        || m.contains("my wife")          ||
                m.contains("my husband")       || m.contains("my mom")           ||
                m.contains("my dad")           || m.contains("my family")        ||
                m.contains("my salary")        || m.contains("my health")        ||
                m.contains("my goal")          || m.contains("my hobby")         ||
                m.contains("my car")           || m.contains("my bike")          ||
                m.contains("my pet")           || m.contains("my skill")         ||
                m.contains("my blood")         || m.contains("my loan")          ||
                m.contains("my emi")           || m.contains("my savings");
    }

    // =========================================================
    // ERROR DETECTION — only real HTTP/API errors, not AI prose
    // =========================================================
    private boolean isErrorResponse(String response) {
        if (response == null || response.isBlank() || response.equals("ERROR")) return true;
        String lower = response.toLowerCase();
        return lower.contains("quota exceeded")    ||
                lower.contains("rate limit")        ||
                lower.contains("\"error\":")        ||
                lower.contains("api key not valid") ||
                lower.contains("invalid api key")   ||
                response.contains("429")            ||
                response.contains("503")            ||
                response.matches("(?s).*\\b(NullPointerException|IOException|" +
                        "RuntimeException|IllegalArgumentException|" +
                        "HttpException|SocketTimeoutException)\\b.*");
    }
}