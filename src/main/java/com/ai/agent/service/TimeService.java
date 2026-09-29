package com.ai.agent.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Tool: returns the current local date and time.
 */
@Service
public class TimeService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.ENGLISH);

    /**
     * @return current local date and time, e.g. "2026-09-29 10:20:35 (Tuesday)"
     */
    public String getCurrentTime() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
