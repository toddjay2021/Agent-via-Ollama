package com.ai.agent.service;

import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Tool: evaluates arithmetic expressions such as "23 * 47 + 128".
 */
@Service
public class CalculatorService {

    /**
     * Evaluates a mathematical expression.
     *
     * @param expression arithmetic expression, may contain + - * / % ^ and parentheses
     * @return the numeric result, or an error message the agent can recover from
     */
    public String calculate(String expression) {
        try {
            String sanitized = expression
                    .replace("\"", "")
                    .replace("'", "")
                    .replace("×", "*")
                    .replace("÷", "/")
                    .replace(",", "")
                    .trim();
            double result = new ExpressionBuilder(sanitized).build().evaluate();
            // Print whole numbers without a trailing ".0"
            if (result == Math.rint(result) && !Double.isInfinite(result) && Math.abs(result) < 1e15) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "Error: cannot evaluate expression '" + expression + "'";
        }
    }
}
