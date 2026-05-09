package com.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiService {

    private final RestClient openAiRestClient;
    private final String model;

    public OpenAiService(RestClient openAiRestClient, @Value("${openai.model}") String model) {
        this.openAiRestClient = openAiRestClient;
        this.model = model;
    }

    @SuppressWarnings("unchecked")
    public String generateReply(GmailService.ReceivedEmail email) {
        // Appel Chat Completions avec RestClient, comme demandé pour Spring Boot 3.
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.4,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "Tu es un agent support commercial. Réponds en français, de façon concise, professionnelle et utile."
                        ),
                        Map.of(
                                "role", "user",
                                "content", """
                                        Rédige une réponse à cet email client.

                                        Sujet : %s
                                        Expéditeur : %s

                                        Email :
                                        %s
                                        """.formatted(email.subject(), email.from(), email.body())
                        )
                )
        );

        Map<String, Object> response = openAiRestClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("choices") == null) {
            throw new IllegalStateException("Réponse OpenAI invalide.");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices.isEmpty()) {
            throw new IllegalStateException("OpenAI n'a retourné aucun choix.");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message.get("content");
        if (content == null || content.toString().isBlank()) {
            throw new IllegalStateException("Réponse générée vide.");
        }

        return content.toString().trim();
    }
}
