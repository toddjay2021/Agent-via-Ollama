package com.ai.agent.controller;

import com.ai.agent.service.AgentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST API: SSE agent stream + Ollama health check.
 */
@RestController
public class AgentController {

    private final AgentService agentService;
    private final String ollamaBaseUrl;
    private final String modelName;
    private final RestClient restClient;

    public AgentController(AgentService agentService,
                           @Value("${ollama.base-url}") String ollamaBaseUrl,
                           @Value("${ollama.model}") String modelName) {
        this.agentService = agentService;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.modelName = modelName;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Streams the agent's Thought/Action/Observation trace as SSE events.
     *
     * @param q the user question
     * @return SSE stream of agent events
     */
    @GetMapping(value = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("Parameter 'q' must not be empty");
        }
        return agentService.run(q.trim());
    }

    /**
     * Lightweight health check: is Ollama reachable and is the model pulled.
     *
     * @return {connected, modelAvailable, model}
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        boolean connected = false;
        boolean modelAvailable = false;
        try {
            String body = restClient.get()
                    .uri(ollamaBaseUrl + "/api/tags")
                    .retrieve()
                    .body(String.class);
            connected = true;
            modelAvailable = body != null && body.contains("\"" + modelName + "\"");
        } catch (Exception ignored) {
            // Ollama not reachable
        }
        return Map.of(
                "connected", connected,
                "modelAvailable", modelAvailable,
                "model", modelName);
    }
}
