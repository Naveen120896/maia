package com.assistant.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GeminiRequest {
    private Object contents = new Object[]{
            new Object(){
                public Object[] parts = new Object[]{
                        new Object(){
                            public String text;
                            public Object init(String t){
                                this.text = t;
                                return this;
                            }
                        }.init("")
                };
            }
    };

    public GeminiRequest(String prompt) {
        this.contents = new Object[]{
                java.util.Map.of(
                        "parts", new Object[]{
                                java.util.Map.of("text", prompt)
                        }
                )
        };
    }
}