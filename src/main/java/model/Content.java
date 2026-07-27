package model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Content {

    private String role;
    private List<GeminiPart> parts;

    public Content() {
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<GeminiPart> getParts() {
        return parts;
    }

    public void setParts(List<GeminiPart> parts) {
        this.parts = parts;
    }
}