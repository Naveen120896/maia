package com.assistant.service;

import com.assistant.client.GeminiClient;
import com.assistant.client.GroqClient;
import com.assistant.entity.UserMemory;
import com.assistant.entity.UserSummary;
import com.assistant.repository.UserMemoryRepository;
import com.assistant.repository.UserSummaryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private final UserMemoryRepository  memoryRepo;
    private final UserSummaryRepository summaryRepo;
    private final GroqClient            groqClient;
    private final GeminiClient          geminiClient;
    private final ObjectMapper          mapper = new ObjectMapper();

    // Junk values the LLM sometimes returns — never save these
    private static final Set<String> JUNK_VALUES = Set.of(
            "null", "unknown", "n/a", "none", "not specified",
            "not available", "na", "nil", "[not specified]",
            "not mentioned", "unspecified", "not applicable"
    );

    // System keys that should never be stored as personal facts
    private static final Set<String> SKIP_KEYS = Set.of(
            "current_date", "today", "date", "time", "current_time",
            "years_worked_in_prodapt_solutions", "years_worked_in_infosys",
            "joined_company"
    );

    public MemoryService(UserMemoryRepository  memoryRepo,
                         UserSummaryRepository summaryRepo,
                         GroqClient            groqClient,
                         GeminiClient          geminiClient) {
        this.memoryRepo   = memoryRepo;
        this.summaryRepo  = summaryRepo;
        this.groqClient   = groqClient;
        this.geminiClient = geminiClient;
    }

    // =========================================================
    // STEP 1 — Called on EVERY user message + PDF uploads.
    //           LLM decides what's personal — returns {} if nothing.
    // STEP 2 — Rebuild summary if any new facts were saved.
    // =========================================================
    @Transactional
    public void extractAndSave(String userId, String userMessage) {

        if (userMessage == null || userMessage.trim().length() < 3) return;

        try {
            String extractPrompt =
                    "You are a personal fact extractor.\n" +
                            "CRITICAL: Use ONLY the exact key names listed below. Do NOT invent new key names.\n" +
                            "CRITICAL: If a value is unknown or not mentioned — SKIP that key. Never save 'unknown', 'N/A', '[not specified]', or any placeholder.\n" +
                            "CRITICAL: Never extract current_date, today's date, or any system/time info — only personal facts.\n" +
                            "CRITICAL: Never create keys like 'years_worked_in_X' — use work_experience for total experience.\n\n" +

                            "Read the message and extract every concrete personal fact about the user.\n" +
                            "The text can be in ANY style — first person, second person, third person, or flowing prose.\n" +
                            "Return ONLY a flat JSON object. No markdown. No explanation. No extra text.\n" +
                            "If there are zero personal facts, return exactly: {}\n\n" +

                            "=== KEYS TO USE ===\n" +
                            "name, nickname, age, birthday, gender, religion, caste, nationality, mother_tongue, marital_status, email, phone,\n" +
                            "city, area, district, state, country, address, pincode,\n" +
                            "job, company, designation, salary, work_experience, non_it_experience, job_start_date, previous_company, total_experience, employer,\n" +
                            "business_name, shop_name, startup_name,\n" +
                            "college, high_school, school, university, degree, branch, graduation_year, qualification, cgpa, percentage,\n" +
                            "wife, husband, mom, dad, father, brother, sister, son, daughter,\n" +
                            "grandfather, grandmother, uncle, aunt, cousin, nephew, niece,\n" +
                            "father_in_law, mother_in_law, family_size,\n" +
                            "girlfriend, boyfriend, partner, best_friend,\n" +
                            "weight, height, blood_group, allergy, disease, medication, diet_type, health_condition,\n" +
                            "bank_name, monthly_income, monthly_expense, loan_amount, emi_amount, savings, insurance_type,\n" +
                            "car, bike, vehicle, laptop_brand, phone_brand, property,\n" +
                            "favourite_food, favourite_movie, favourite_music, favourite_sport, favourite_color, favourite_book,\n" +
                            "hobby, sport, reading_genre,\n" +
                            "programming_language, spoken_language, framework, database, tools, skill, expertise,\n" +
                            "career_goal, financial_goal, health_goal, life_goal,\n" +
                            "wake_time, sleep_time, daily_routine,\n" +
                            "pet_type, pet_name\n\n" +

                            "=== EXAMPLES ===\n" +
                            "'My name is Naveen'                        → {\"name\":\"Naveen\"}\n" +
                            "'I am 28 years old'                        → {\"age\":\"28\"}\n" +
                            "'I live in Chennai, Tambaram area'         → {\"city\":\"Chennai\",\"area\":\"Tambaram\"}\n" +
                            "'I work as a Java developer at TCS'        → {\"job\":\"Java developer\",\"company\":\"TCS\"}\n" +
                            "'My wife is Jeevi and mom is Sowndhari'    → {\"wife\":\"Jeevi\",\"mom\":\"Sowndhari\"}\n" +
                            "'I know Java and Spring Boot'              → {\"programming_language\":\"Java\",\"framework\":\"Spring Boot\"}\n" +
                            "'achieving a 6.36 CGPA in B.E. Mechanical'→ {\"cgpa\":\"6.36\",\"degree\":\"B.E. Mechanical Engineering\"}\n" +
                            "'born on 12th August 1996'                 → {\"birthday\":\"12 August 1996\"}\n" +
                            "'unmarried, Indian by nationality'         → {\"marital_status\":\"unmarried\",\"nationality\":\"Indian\"}\n" +
                            "'1.6 years of IT work at Infosys'          → {\"previous_company\":\"Infosys\",\"work_experience\":\"1.6 years\"}\n" +
                            "'joined IBM on 31 January 2024'            → {\"company\":\"IBM\",\"job_start_date\":\"31 January 2024\"}\n\n" +

                            "Message: " + userMessage;

            System.out.println("🧠 [MemoryService] Extracting from: " +
                    userMessage.substring(0, Math.min(80, userMessage.length())) + "...");

            String raw = callForExtraction(extractPrompt);

            System.out.println("🧠 [MemoryService] Raw LLM response: [" + raw + "]");

            if (raw == null || raw.isBlank()) {
                System.out.println("⚠️ [MemoryService] Empty response — skipping");
                return;
            }

            // Strip markdown fences if model misbehaves
            raw = raw.replaceAll("(?s)```json", "")
                    .replaceAll("(?s)```", "")
                    .trim();

            // Find JSON boundaries
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');

            if (start == -1 || end == -1 || end <= start) {
                System.out.println("⚠️ [MemoryService] No valid JSON in response: " + raw);
                return;
            }

            raw = raw.substring(start, end + 1);
            System.out.println("🧠 [MemoryService] Parsed JSON: [" + raw + "]");

            if (raw.equals("{}")) {
                System.out.println("ℹ️ [MemoryService] No personal facts in this message — skipping");
                return;
            }

            // Parse, filter junk, and upsert
            JsonNode facts = mapper.readTree(raw);
            Iterator<Map.Entry<String, JsonNode>> fields = facts.fields();
            int count = 0;

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase().trim().replaceAll("\\s+", "_");
                String val = entry.getValue().asText().trim();

                // Skip system keys
                if (SKIP_KEYS.contains(key)) {
                    System.out.println("⏭️ [MemoryService] Skipped system key → [" + key + "]");
                    continue;
                }

                // Skip junk values
                if (key.isBlank() || val.isBlank() || JUNK_VALUES.contains(val.toLowerCase().trim())) {
                    System.out.println("⏭️ [MemoryService] Skipped junk → [" + key + "] = [" + val + "]");
                    continue;
                }

                memoryRepo.upsertMemory(userId, key, val);
                System.out.println("✅ [MemoryService] Saved → [" + key + "] = [" + val + "]");
                count++;
            }

            System.out.println("🧠 [MemoryService] Total facts saved: " + count);

            if (count > 0) {
                memoryRepo.flush();
                rebuildSummary(userId);
            }

        } catch (Exception e) {
            System.out.println("❌ [MemoryService] extractAndSave failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================
    // Rebuild natural-language summary from all stored facts.
    // REQUIRES_NEW so it sees rows flushed by the parent tx.
    // =========================================================
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildSummary(String userId) {
        try {
            List<UserMemory> memories = memoryRepo.findByUserId(userId);
            if (memories.isEmpty()) {
                System.out.println("⚠️ [MemoryService] rebuildSummary: no memories found for " + userId);
                return;
            }

            StringBuilder facts = new StringBuilder();
            for (UserMemory m : memories) {
                facts.append(m.getMemoryKey())
                        .append(": ").append(m.getMemoryValue()).append("\n");
            }

            String summaryPrompt =
                    "Write a 2-3 sentence factual summary about a person using ONLY the facts listed below.\n" +
                            "STRICT RULES:\n" +
                            "  - Use ONLY information explicitly present in the facts\n" +
                            "  - Do NOT add any details not in the list\n" +
                            "  - Do NOT say 'though specifics are unknown' or ask for more info\n" +
                            "  - If gender is not in facts, do not assume he/she — use 'they'\n" +
                            "  - 2-3 sentences max. No bullet points.\n\n" +
                            "Facts:\n" + facts;

            System.out.println("📝 [MemoryService] Rebuilding summary for: " + userId);

            String summary = callForExtraction(summaryPrompt);

            if (summary == null || summary.isBlank()) {
                System.out.println("⚠️ [MemoryService] Summary generation returned empty");
                return;
            }

            Optional<UserSummary> existing = summaryRepo.findByUserId(userId);
            UserSummary us = existing.orElse(new UserSummary());
            us.setUserId(userId);
            us.setSummary(summary.trim());
            summaryRepo.save(us);

            System.out.println("📝 [MemoryService] Summary saved: " + summary.trim());

        } catch (Exception e) {
            System.out.println("❌ [MemoryService] rebuildSummary failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================
    // GET summary for a user (used in ChatService.buildPrompt)
    // =========================================================
    public Optional<UserSummary> getSummary(String userId) {
        return summaryRepo.findByUserId(userId);
    }

    // =========================================================
    // GET all key-value memories for a user
    // =========================================================
    public List<UserMemory> getMemories(String userId) {
        return memoryRepo.findByUserId(userId);
    }

    // =========================================================
    // FORMAT memory dump for "show memory" command
    // =========================================================
    public String formatMemoryDump(String userId) {
        Optional<UserSummary> summary  = summaryRepo.findByUserId(userId);
        List<UserMemory>      memories = memoryRepo.findByUserId(userId);

        if (memories.isEmpty() && summary.isEmpty()) {
            return "🧠 No memories stored yet.";
        }

        StringBuilder sb = new StringBuilder();

        summary.ifPresent(s -> {
            sb.append("🧠 Summary:\n");
            sb.append(s.getSummary()).append("\n\n");
        });

        if (!memories.isEmpty()) {
            sb.append("📋 Key facts:\n");
            printCategory(sb, memories, "IDENTITY",    new String[]{"name","nickname","age","birthday","gender","religion","caste","nationality","mother_tongue","marital_status","email","phone"});
            printCategory(sb, memories, "LOCATION",    new String[]{"city","area","district","state","country","address","pincode"});
            printCategory(sb, memories, "WORK",        new String[]{"job","company","designation","salary","monthly_income","work_experience","non_it_experience","total_experience","job_start_date","previous_company","employer","business_name","startup_name","shop_name"});
            printCategory(sb, memories, "EDUCATION",   new String[]{"college","high_school","school","university","degree","branch","graduation_year","qualification","cgpa","percentage"});
            printCategory(sb, memories, "FAMILY",      new String[]{"wife","husband","mom","dad","father","brother","sister","son","daughter","grandfather","grandmother","uncle","aunt","cousin","nephew","niece","father_in_law","father_name","mother_in_law","family_size"});
            printCategory(sb, memories, "RELATIONS",   new String[]{"girlfriend","boyfriend","partner","best_friend"});
            printCategory(sb, memories, "HEALTH",      new String[]{"weight","height","blood_group","allergy","disease","medication","diet_type","health_condition"});
            printCategory(sb, memories, "FINANCE",     new String[]{"bank_name","monthly_income","monthly_expense","loan_amount","emi_amount","savings","insurance_type"});
            printCategory(sb, memories, "ASSETS",      new String[]{"car","bike","vehicle","laptop_brand","phone_brand","property"});
            printCategory(sb, memories, "PREFERENCES", new String[]{"favourite_food","favourite_movie","favourite_music","favourite_sport","favourite_color","favourite_book"});
            printCategory(sb, memories, "HOBBIES",     new String[]{"hobby","sport","reading_genre"});
            printCategory(sb, memories, "SKILLS",      new String[]{"programming_language","spoken_language","framework","database","tools","skill","expertise"});
            printCategory(sb, memories, "GOALS",       new String[]{"career_goal","financial_goal","health_goal","life_goal"});
            printCategory(sb, memories, "ROUTINE",     new String[]{"wake_time","sleep_time","daily_routine"});
            printCategory(sb, memories, "PETS",        new String[]{"pet_type","pet_name"});

            // Anything LLM invented not in known keys
            Set<String> categorised = getCategorisedKeys();
            boolean hasOther = memories.stream().anyMatch(m -> !categorised.contains(m.getMemoryKey()));
            if (hasOther) {
                sb.append("\n  OTHER:\n");
                for (UserMemory m : memories) {
                    if (!categorised.contains(m.getMemoryKey())) {
                        sb.append("    • ").append(m.getMemoryKey())
                                .append(": ").append(m.getMemoryValue()).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private void printCategory(StringBuilder sb, List<UserMemory> memories,
                               String label, String[] keys) {
        Set<String> keySet = new HashSet<>(Arrays.asList(keys));
        List<UserMemory> matching = memories.stream()
                .filter(m -> keySet.contains(m.getMemoryKey()))
                .collect(Collectors.toList());

        if (!matching.isEmpty()) {
            sb.append("\n  ").append(label).append(":\n");
            for (UserMemory m : matching) {
                sb.append("    • ").append(m.getMemoryKey())
                        .append(": ").append(m.getMemoryValue()).append("\n");
            }
        }
    }

    private Set<String> getCategorisedKeys() {
        return new HashSet<>(Arrays.asList(
                "name","nickname","age","birthday","gender","religion","caste","nationality","mother_tongue","marital_status","email","phone",
                "city","area","district","state","country","address","pincode","mobile","date_of_birth",
                "job","company","designation","salary","monthly_income","work_experience","non_it_experience","total_experience","job_start_date","previous_company","employer","business_name","startup_name","shop_name",
                "college","high_school","school","university","degree","branch","graduation_year","qualification","cgpa","percentage",
                "wife","husband","mom","dad","father","brother","sister","son","daughter","grandfather","grandmother","uncle","aunt","cousin","nephew","niece","father_in_law","mother_in_law","family_size",
                "girlfriend","boyfriend","partner","best_friend",
                "weight","height","blood_group","allergy","disease","medication","diet_type","health_condition",
                "bank_name","monthly_income","monthly_expense","loan_amount","emi_amount","savings","insurance_type",
                "car","bike","vehicle","laptop_brand","phone_brand","property",
                "favourite_food","favourite_movie","favourite_music","favourite_sport","favourite_color","favourite_book",
                "hobby","sport","reading_genre",
                "programming_language","spoken_language","framework","database","tools","skill","expertise",
                "career_goal","financial_goal","health_goal","life_goal",
                "wake_time","sleep_time","daily_routine",
                "pet_type","pet_name"
        ));
    }

    // =========================================================
    // CLEAR all memory + summary for a user
    // =========================================================
    @Transactional
    public void clearMemory(String userId) {
        memoryRepo.deleteByUserId(userId);
        summaryRepo.deleteByUserId(userId);
        System.out.println("🧹 [MemoryService] Memory + Summary cleared for: " + userId);
    }

    // =========================================================
    // INTERNAL — Groq first, Gemini fallback
    // =========================================================
    private String callForExtraction(String prompt) {
        try {
            String raw = groqClient.generate(prompt);
            if (raw != null && !raw.isBlank()) {
                System.out.println("🟣 [MemoryService] Groq responded OK");
                return raw;
            }
        } catch (Exception e) {
            System.out.println("⚠️ [MemoryService] Groq failed: " + e.getMessage() + " → trying Gemini");
        }

        try {
            String raw = geminiClient.generate(prompt);
            if (raw != null && !raw.isBlank()) {
                System.out.println("🔵 [MemoryService] Gemini responded OK");
                return raw;
            }
        } catch (Exception e) {
            System.out.println("❌ [MemoryService] Gemini also failed: " + e.getMessage());
        }

        return null;
    }
}