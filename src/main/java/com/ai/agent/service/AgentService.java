package com.ai.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReAct agent: runs a Thought -&gt; Action -&gt; Observation loop with the LLM
 * and streams every step to the browser as SSE events.
 *
 * <p>Emitted SSE events:
 * <ul>
 *   <li>thought     - {text}</li>
 *   <li>action      - {tool, arguments, display}</li>
 *   <li>observation - {text}</li>
 *   <li>final_answer- {text}</li>
 *   <li>agent_error - {message}</li>
 *   <li>done        - {steps, durationMs}</li>
 * </ul>
 */
@Service
public class AgentService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful AI assistant that can use tools to answer the user's question.

            Available tools:
            1. calculate - Evaluates a mathematical expression and returns the numeric result.
               Usage: calculate(<expression>)   Example: calculate(2 + 3 * 4)
            2. get_current_time - Returns the current date and time. Takes no arguments.
               Usage: get_current_time()

            To answer the user's question you MUST use exactly this format:

            When you need to use a tool:
            Thought: <your reasoning about what to do next>
            Action: <the tool call, e.g. get_current_time() or calculate(25 * 4 + 10)>

            When you already know the answer:
            Thought: <your reasoning>
            Final Answer: <your answer to the user's question, written in the user's language>

            Rules:
            - Every reply MUST start with "Thought:".
            - Output only ONE Thought/Action pair at a time, then STOP and wait for the Observation.
            - Never write "Observation:" yourself - only the system provides it.
            - When you know the answer, reply with "Thought:" followed by "Final Answer:".
            - When using calculate, copy the user's expression exactly and completely, including all parts and parentheses.
            - If the user's question has multiple parts, take an Action for EVERY part before answering.
            - The Final Answer is the only reply the user sees. It must include ALL information asked for, combining every Observation you received.
            - Your Final Answer MUST use the exact values from the Observations you received (dates, times, numbers). Never invent values.
            - Always reply in the same language the user used.

            Complete example:

            Question: What time is it now?
            Thought: The user asks for the current time, I need to call the get_current_time tool.
            Action: get_current_time()
            Observation: 2026-09-29 10:20:35 (Tuesday)
            Thought: I got the current time from the observation, I can answer the question directly.
            Final Answer: It's 2026-09-29 10:20:35 (Tuesday) right now.

            More examples:

            Question: What is 25 * 4 + 10?
            Thought: This is a math question, I should use the calculate tool.
            Action: calculate(25 * 4 + 10)
            Observation: 110
            Thought: The calculation result is 110, now I can answer.
            Final Answer: 25 * 4 + 10 = 110

            Question: What time is it, and what is 6 * 7?
            Thought: The user asks two things: the current time and a multiplication. First I need the current time.
            Action: get_current_time()
            Observation: 2026-06-15 09:30:00 (Monday)
            Thought: I have the time. Now I need to calculate 6 * 7.
            Action: calculate(6 * 7)
            Observation: 42
            Thought: I now have both the time and the calculation result, I can answer both parts of the question.
            Final Answer: It's 2026-06-15 09:30:00 (Monday), and 6 * 7 = 42.

            Question: Who wrote Romeo and Juliet?
            Thought: This is general knowledge, I can answer directly without any tools.
            Final Answer: Romeo and Juliet was written by William Shakespeare.
            """;

    private final OllamaChatModel chatModel;
    private final CalculatorService calculatorService;
    private final TimeService timeService;
    private final int maxSteps;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-runner");
        t.setDaemon(true);
        return t;
    });

    public AgentService(OllamaChatModel chatModel,
                        CalculatorService calculatorService,
                        TimeService timeService,
                        @Value("${agent.max-steps:5}") int maxSteps) {
        this.chatModel = chatModel;
        this.calculatorService = calculatorService;
        this.timeService = timeService;
        this.maxSteps = maxSteps;
    }

    /**
     * Starts an agent run for the given question. Every reasoning step is
     * streamed to the client through the returned emitter.
     *
     * @param question user question
     * @return SSE emitter streaming the Thought/Action/Observation trace
     */
    public SseEmitter run(String question) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> execute(question, emitter));
        return emitter;
    }

    private void execute(String question, SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        int steps = 0;
        int nudges = 0;
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            messages.add(UserMessage.from(question));

            for (int iteration = 1; iteration <= maxSteps; iteration++) {
                Response<AiMessage> response = chatModel.generate(messages);
                steps++; // one LLM call = one reasoning step (tool round or final-answer round)
                String output = response.content().text();
                LOG.info("Iteration {} -> {}", iteration, output == null ? "" : output.replace('\n', ' '));

                ParsedStep step = parse(output);

                if (step.action() != null) {
                    if (step.thought() != null) {
                        send(emitter, "thought", Map.of("text", step.thought()));
                    }
                    send(emitter, "action", Map.of(
                            "tool", step.toolName(),
                            "arguments", step.arguments(),
                            "display", step.action()));
                    String observation = executeTool(step.toolName(), step.arguments());
                    send(emitter, "observation", Map.of("text", observation));

                    // Keep the transcript clean: only the Thought/Action part is remembered
                    // so a hallucinated Observation never pollutes the conversation.
                    String assistantTurn = step.thought() == null
                            ? "Action: " + step.action()
                            : "Thought: " + step.thought() + "\nAction: " + step.action();
                    messages.add(AiMessage.from(assistantTurn));
                    messages.add(UserMessage.from("Observation: " + observation));
                    continue;
                }

                if (step.finalAnswer() == null && nudges < 2) {
                    // Model stalled (only a Thought, or nothing usable) - nudge it to answer
                    nudges++;
                    if (output != null && !output.isBlank()) {
                        messages.add(AiMessage.from(output.trim()));
                    }
                    messages.add(UserMessage.from(
                            "Continue: reply with your Final Answer now, using the exact values from the"
                                    + " Observations above. Format:\nThought: <reasoning>\nFinal Answer: <answer>"));
                    continue;
                }

                // Terminal step: final answer (or a clean fallback when the model broke the format)
                String answer = step.finalAnswer() != null ? step.finalAnswer()
                        : step.thought() != null ? step.thought()
                        : (output == null || output.isBlank()
                                ? "Sorry, I could not find an answer."
                                : output.trim());
                if (step.thought() != null && step.finalAnswer() != null) {
                    send(emitter, "thought", Map.of("text", step.thought()));
                }
                send(emitter, "final_answer", Map.of("text", answer));
                sendDone(emitter, steps, startedAt);
                emitter.complete();
                return;
            }

            // Exhausted all iterations without a final answer
            send(emitter, "final_answer", Map.of(
                    "text", "Sorry, I could not complete this task within " + maxSteps + " reasoning steps."));
            sendDone(emitter, steps, startedAt);
            emitter.complete();
        } catch (Exception e) {
            LOG.error("Agent run failed", e);
            trySend(emitter, "agent_error", Map.of(
                    "message", "Agent execution failed: " + rootMessage(e)));
            sendDone(emitter, steps, startedAt);
            completeQuietly(emitter);
        }
    }

    /* ------------------------------ tool dispatch ------------------------------ */

    /** @return true when the name matches one of the registered tools (or an alias). */
    private boolean isKnownTool(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "calculate", "calculator", "get_current_time", "current_time", "get_time" -> true;
            default -> false;
        };
    }

    /**
     * Executes a tool by name and returns the observation text.
     * Unknown tools produce an error observation so the model can recover.
     */
    private String executeTool(String toolName, String arguments) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        LOG.info("Executing tool '{}' with args '{}'", name, arguments);
        return switch (name) {
            case "calculate", "calculator" -> calculatorService.calculate(arguments == null ? "" : arguments);
            case "get_current_time", "current_time", "get_time" -> timeService.getCurrentTime();
            default -> "Error: unknown tool '" + toolName
                    + "'. Available tools: calculate(expression), get_current_time()";
        };
    }

    /* ------------------------------ ReAct output parsing ------------------------------ */

    /** Result of parsing one model output into a ReAct step. */
    private record ParsedStep(String thought, String action, String toolName, String arguments,
                              String finalAnswer) {
    }

    /** A tool call extracted from an "Action:" line. */
    private record ToolCall(String name, String args, String display) {
    }

    /**
     * Parses one assistant output. Precedence: when an Action appears before a
     * Final Answer (model kept going in one turn), the Action wins because the
     * observation must come from a real tool execution.
     */
    private ParsedStep parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedStep(null, null, null, null, null);
        }
        String text = raw.replace("```", "").trim();

        int actionIdx = indexOfIgnoreCase(text, "action:");
        int finalIdx = indexOfIgnoreCase(text, "final answer:");

        // The thought starts after "thought:" - but small models sometimes mislabel
        // their first line as "Observation:" instead. Since the model should never
        // write observations itself, treat such a leading line as the thought too.
        String thoughtMarker = "thought:";
        int thoughtIdx = indexOfIgnoreCase(text, thoughtMarker);
        if (thoughtIdx < 0) {
            int obsIdx = indexOfIgnoreCase(text, "observation:");
            if (obsIdx >= 0
                    && (actionIdx < 0 || obsIdx < actionIdx)
                    && (finalIdx < 0 || obsIdx < finalIdx)) {
                thoughtMarker = "observation:";
                thoughtIdx = obsIdx;
            }
        }

        String thought = null;
        if (thoughtIdx >= 0) {
            int from = thoughtIdx + thoughtMarker.length();
            int to = text.length();
            if (actionIdx > thoughtIdx) {
                to = actionIdx;
            }
            if (finalIdx > thoughtIdx && finalIdx < to) {
                to = finalIdx;
            }
            String candidate = text.substring(from, to).trim();
            thought = candidate.isEmpty() ? null : candidate;
        }

        boolean actionWins = actionIdx >= 0 && (finalIdx < 0 || actionIdx < finalIdx);

        if (actionWins) {
            String afterAction = text.substring(actionIdx + "action:".length());
            String actionLine = firstLine(afterAction).replace("`", "").trim();
            // LangChain-style "Action:" + "Action Input:" on the next line
            if (!actionLine.contains("(")) {
                int inputIdx = indexOfIgnoreCase(afterAction, "action input:");
                if (inputIdx >= 0) {
                    String input = firstLine(afterAction.substring(inputIdx + "action input:".length())).trim();
                    if (!input.isEmpty()) {
                        actionLine = actionLine + "(" + input + ")";
                    }
                }
            }
            ToolCall call = parseToolCall(actionLine);
            if (call != null && (actionLine.contains("(") || isKnownTool(call.name()))) {
                return new ParsedStep(thought, call.display(), call.name(), call.args(), null);
            }
            // The model wrote its answer after "Action:" (e.g. "Action: Romeo and Juliet
            // was written by ...") instead of using "Final Answer:" - treat it as the answer.
            // If the model kept going and hallucinated a full trace, prefer its Final Answer.
            String answer;
            int laterFinalIdx = indexOfIgnoreCase(afterAction, "final answer:");
            if (laterFinalIdx >= 0) {
                answer = cleanAnswer(afterAction.substring(laterFinalIdx + "final answer:".length()));
            } else {
                answer = cleanAnswer(afterAction);
            }
            if (!answer.isEmpty()) {
                return new ParsedStep(thought, null, null, null, answer);
            }
        }

        if (finalIdx >= 0) {
            String answer = cleanAnswer(text.substring(finalIdx + "final answer:".length()));
            if (!answer.isEmpty()) {
                return new ParsedStep(thought, null, null, null, answer);
            }
        }

        // Nothing matched - caller falls back to treating the raw text as the answer
        return new ParsedStep(thought, null, null, null, null);
    }

    /** Splits an action line like "calculate(2 + 3 * 4)" into name, args and display form. */
    private ToolCall parseToolCall(String actionLine) {
        String s = actionLine.trim();
        if (s.isEmpty()) {
            return null;
        }
        // The model sometimes chains two calls in one line, e.g.
        // "get_current_time() and calculate(2 + 3)". Execute only the first one.
        int andChain = indexOfIgnoreCase(s, ") and ");
        if (andChain >= 0) {
            s = s.substring(0, andChain + 1);
        }
        int open = s.indexOf('(');
        if (open > 0) {
            String name = s.substring(0, open).trim();
            if (name.isEmpty()) {
                return null;
            }
            int close = s.lastIndexOf(')');
            String args = close > open ? s.substring(open + 1, close) : s.substring(open + 1);
            args = stripQuotes(args.trim());
            return new ToolCall(name, args, name + "(" + args + ")");
        }
        // Bare tool name without parentheses
        String name = s.split("\\s+")[0];
        return new ToolCall(name, "", name + "()");
    }

    /* ------------------------------ SSE helpers ------------------------------ */

    private void send(SseEmitter emitter, String event, Map<String, Object> data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    }

    private void trySend(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            send(emitter, event, data);
        } catch (Exception e) {
            LOG.debug("SSE send failed for event '{}': {}", event, e.getMessage());
        }
    }

    private void sendDone(SseEmitter emitter, int steps, long startedAt) {
        trySend(emitter, "done", Map.of(
                "steps", steps,
                "durationMs", System.currentTimeMillis() - startedAt));
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Client already gone
        }
    }

    /* ------------------------------ misc helpers ------------------------------ */

    /**
     * Trims an extracted answer and cuts off a hallucinated continuation:
     * stops at the start of a new ReAct marker line ("Thought:", "Observation:",
     * "Action:", "Final Answer:", "Question:").
     */
    private static String cleanAnswer(String s) {
        int cut = Integer.MAX_VALUE;
        for (String marker : new String[]{
                "\nObservation:", "\nThought:", "\nAction:", "\nFinal Answer:", "\nQuestion:"}) {
            int i = indexOfIgnoreCase(s, marker);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        if (cut != Integer.MAX_VALUE) {
            s = s.substring(0, cut);
        }
        return s.replace("**", "").trim();
    }

    private static int indexOfIgnoreCase(String text, String token) {
        final int len = token.length();
        for (int i = 0; i + len <= text.length(); i++) {
            if (text.regionMatches(true, i, token, 0, len)) {
                return i;
            }
        }
        return -1;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
