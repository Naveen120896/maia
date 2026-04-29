package com.assistant.controller;

import com.assistant.dto.ChatRequest;
import com.assistant.dto.ChatResponse;
import com.assistant.repository.UserMemoryRepository;
import com.assistant.service.ChatService;
import com.assistant.service.MemoryService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService          chatService;
    private final MemoryService        memoryService;
    private final UserMemoryRepository memoryRepo;

    public ChatController(ChatService chatService,
                          MemoryService memoryService,
                          UserMemoryRepository memoryRepo) {
        this.chatService   = chatService;
        this.memoryService = memoryService;
        this.memoryRepo    = memoryRepo;
    }

    // ── Text chat ──
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String response = chatService.chat(request.getUserId(), request.getMessage());
        return new ChatResponse(response);
    }

    // ── Document upload + chat ──
    // POST /chat/document
    // Form fields:
    //   file   — PDF or .txt file
    //   userId — user id string
    //   mode   — "ask" | "improve"
    //   prompt — (optional) user's question when mode=ask
    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatResponse> documentChat(
            @RequestParam("file")                        MultipartFile file,
            @RequestParam(defaultValue = "default")      String userId,
            @RequestParam(defaultValue = "ask")          String mode,
            @RequestParam(defaultValue = "")             String prompt
    ) {
        try {
            // ── Extract text from PDF or plain text file ──
            String docText = extractText(file);

            if (docText == null || docText.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(new ChatResponse("❌ Could not extract text from the file. Make sure it is a readable PDF or .txt file."));
            }

            // ── KEY FIX: Extract and save personal facts from document into memory ──
            // Runs for both "ask" and "improve" modes — resume, profile, any personal doc
            // LLM returns {} if no personal facts found — safe to always call
            memoryService.extractAndSave(userId, docText);

            // ── Build message for ChatService ──
            String message;
            if ("improve".equalsIgnoreCase(mode)) {
                message = "The user has uploaded a document and wants you to improve it.\n" +
                        "Rewrite and enhance the entire document professionally.\n" +
                        "Keep ALL factual content — names, dates, companies, skills — exactly as they are.\n" +
                        "Improve: clarity, structure, grammar, formatting, and professional language.\n" +
                        "You MUST return the full improved document text. Do NOT summarise it. Do NOT say you cannot generate it.\n" +
                        "Do NOT add any intro, commentary, or explanation — output ONLY the improved document.\n\n" +
                        "=== DOCUMENT TO IMPROVE ===\n" + docText + "\n=== END ===\n\n" +
                        "Now write the full improved version:";
            } else {
                String userQuestion = prompt.isBlank()
                        ? "Please summarize this document and tell me the key points."
                        : prompt;
                message = "DOCUMENT_QUESTION_REQUEST\n" +
                        "The user has uploaded a document and asked: \"" + userQuestion + "\"\n" +
                        "Answer based strictly on the document content below.\n\n" +
                        "=== DOCUMENT START ===\n" + docText + "\n=== DOCUMENT END ===";
            }

            String aiResponse = chatService.chat(userId, message);
            return ResponseEntity.ok(new ChatResponse(aiResponse));

        } catch (Exception e) {
            System.out.println("❌ Document chat error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("❌ Failed to process document: " + e.getMessage()));
        }
    }

    // ── Test memory endpoint ──
    @GetMapping("/test-memory")
    public String testMemory() {
        memoryService.extractAndSave("naveen",
                "Hi I am Naveen I am 29 years old I live in Chennai");
        return memoryRepo.findByUserId("naveen").toString();
    }

    // ── Helper: extract plain text from PDF or .txt ──
    private String extractText(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        if (name.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(doc).trim();
            }
        } else {
            // Treat as plain text (.txt, .md, etc.)
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }
}