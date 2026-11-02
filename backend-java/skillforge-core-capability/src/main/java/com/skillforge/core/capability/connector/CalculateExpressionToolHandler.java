package com.skillforge.core.capability.connector;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 使用受限四则运算语法进行十进制计算，不执行脚本。 */
@Component
public class CalculateExpressionToolHandler implements PlatformToolHandler {

    public static final int MAX_EXPRESSION_LENGTH = 8192;
    private static final int MAX_PARSE_DEPTH = 64;
    private static final MathContext MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);

    @Override
    public String toolName() {
        return "calculate_expression";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        String expression = args == null || args.get("expression") == null
                ? "" : String.valueOf(args.get("expression")).replaceAll("\\s+", "");
        if (expression.isBlank() || expression.length() > MAX_EXPRESSION_LENGTH) {
            return Map.of("success", false, "error", "expression 不能为空且长度不得超过 " + MAX_EXPRESSION_LENGTH + "");
        }
        try {
            BigDecimal value = new Parser(expression).parse();
            return Map.of("success", true, "value", printable(value));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private static String printable(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static final class Parser {
        private final String input;
        private int index;
        private int depth;

        private Parser(String input) {
            this.input = input;
        }

        private BigDecimal parse() {
            BigDecimal value = expression();
            if (index != input.length()) {
                throw new IllegalArgumentException("expression 包含不支持的字符");
            }
            return value;
        }

        private BigDecimal expression() {
            BigDecimal value = term();
            while (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                char operator = input.charAt(index++);
                BigDecimal right = term();
                value = operator == '+' ? value.add(right, MATH_CONTEXT) : value.subtract(right, MATH_CONTEXT);
            }
            return value;
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (index < input.length() && (input.charAt(index) == '*' || input.charAt(index) == '/')) {
                char operator = input.charAt(index++);
                BigDecimal right = factor();
                if (operator == '/' && right.signum() == 0) {
                    throw new ArithmeticException("不能除以零");
                }
                value = operator == '*' ? value.multiply(right, MATH_CONTEXT) : value.divide(right, MATH_CONTEXT);
            }
            return value;
        }

        private BigDecimal factor() {
            if (++depth > MAX_PARSE_DEPTH) throw new IllegalArgumentException("表达式嵌套超过解析深度");
            try { return factorValue(); }
            finally { depth--; }
        }

        private BigDecimal factorValue() {
            if (index >= input.length()) {
                throw new IllegalArgumentException("expression 不完整");
            }
            char current = input.charAt(index);
            if (current == '+') {
                index++;
                return factor();
            }
            if (current == '-') {
                index++;
                return factor().negate(MATH_CONTEXT);
            }
            if (current == '(') {
                index++;
                BigDecimal value = expression();
                if (index >= input.length() || input.charAt(index++) != ')') {
                    throw new IllegalArgumentException("括号未闭合");
                }
                return value;
            }
            int start = index;
            boolean decimal = false;
            while (index < input.length()) {
                char character = input.charAt(index);
                if (Character.isDigit(character)) {
                    index++;
                } else if (character == '.' && !decimal) {
                    decimal = true;
                    index++;
                } else {
                    break;
                }
            }
            if (start == index || ".".equals(input.substring(start, index))) {
                throw new IllegalArgumentException("expression 仅支持数字、+、-、*、/ 和括号");
            }
            return new BigDecimal(input.substring(start, index), MATH_CONTEXT);
        }
    }
}
