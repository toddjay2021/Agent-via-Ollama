package com.ai.agent.config;

import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for the Ollama chat model used by the agent.
 */
@Configuration
public class OllamaConfig {

    /**
     * Creates the LLM client backed by a local Ollama server.
     *
     * @param baseUrl     Ollama REST endpoint, e.g. http://localhost:11434
     * @param model       model tag to use, e.g. llama3.2:1b
     * @param temperature sampling temperature (low value keeps the ReAct format stable)
     * @return configured OllamaChatModel
     */
    @Bean
    public OllamaChatModel ollamaChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model}") String model,
            @Value("${ollama.temperature}") double temperature) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(90))
                .build();
    }
}
