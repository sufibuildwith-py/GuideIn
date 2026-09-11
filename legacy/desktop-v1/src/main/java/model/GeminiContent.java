package model;

import java.util.List;

public class GeminiContent {

    private List<GeminiPart> parts;

    public GeminiContent() {
    }

    public GeminiContent(List<GeminiPart> parts) {
        this.parts = parts;
    }

    public List<GeminiPart> getParts() {
        return parts;
    }

    public void setParts(List<GeminiPart> parts) {
        this.parts = parts;
    }
}