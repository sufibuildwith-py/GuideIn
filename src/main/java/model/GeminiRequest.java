package model;

import java.util.List;

public class GeminiRequest {

    private List<GeminiContent> contents;

    public GeminiRequest() {
    }

    public GeminiRequest(List<GeminiContent> contents) {
        this.contents = contents;
    }

    public GeminiRequest(String prompt) {
        this.contents = List.of(
                new GeminiContent(
                        List.of(
                                new GeminiPart(prompt)
                        )
                )
        );
    }

    public List<GeminiContent> getContents() {
        return contents;
    }

    public void setContents(List<GeminiContent> contents) {
        this.contents = contents;
    }
}