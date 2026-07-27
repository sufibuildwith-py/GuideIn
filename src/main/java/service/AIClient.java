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

    private final HttpClient client;
    private final ObjectMapper mapper;

    public AIClient() {
        this.client = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    public void sendMessage(Message message) {

        try {

            GeminiRequest geminiRequest =
                    new GeminiRequest(
                            message.getContent()
                    );

            String requestBody =
                    mapper.writeValueAsString(
                            geminiRequest
                    );

            String url =
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + Config.MODEL
                            + ":generateContent?key="
                            + Config.API_KEY;

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(requestBody)
                            )
                            .build();

            HttpResponse<String> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {
                System.out.println(
                        "Request failed. Status Code: "
                                + response.statusCode()
                );
                System.out.println(response.body());
                return;
            }

            GeminiResponse geminiResponse =
                    mapper.readValue(
                            response.body(),
                            GeminiResponse.class
                    );

            String aiText =
                    geminiResponse
                            .getCandidates()
                            .get(0)
                            .getContent()
                            .getParts()
                            .get(0)
                            .getText();

            System.out.println("\nAI: " + aiText);

        } catch (Exception e) {
            System.out.println(
                    "Error: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }
}