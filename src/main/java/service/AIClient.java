package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.Config;
import model.GeminiRequest;
import model.GeminiResponse;
import model.Message;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AIClient {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String getReply(Message message) {
        try {
            GeminiRequest requestBody = new GeminiRequest(message.getContent());

            String body = mapper.writeValueAsString(requestBody);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + Config.MODEL
                    + ":generateContent?key="
                    + Config.API_KEY;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Gemini returned HTTP " + response.statusCode()
                );
            }

            GeminiResponse result = mapper.readValue(
                    response.body(),
                    GeminiResponse.class
            );

            if (result.getCandidates() == null ||
                    result.getCandidates().isEmpty() ||
                    result.getCandidates().get(0).getContent() == null ||
                    result.getCandidates().get(0).getContent().getParts() == null ||
                    result.getCandidates().get(0).getContent().getParts().isEmpty()) {
                throw new IllegalStateException("Gemini returned no usable text.");
            }

            return result.getCandidates()
                    .get(0)
                    .getContent()
                    .getParts()
                    .get(0)
                    .getText();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
