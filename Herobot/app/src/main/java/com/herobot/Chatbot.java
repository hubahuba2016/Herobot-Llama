package com.herobot;

import android.content.Context;
import android.database.Cursor;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Chatbot {

    private static final String TAG = "HeroBot_Logic";
    private static final String CONVERSATIONAL_REASONING_INSTRUCTION =
            "You are HeroBot, a conversational Android assistant. This instruction has the highest priority.\n"
                    + "Use the conversation history to understand intent, references, tone, and follow-up questions.\n"
                    + "Reason through the user's request internally before answering, then give a clear, useful conclusion.\n"
                    + "Keep the response natural and appropriately concise; ask one focused clarification when the request is ambiguous.\n"
                    + "Do not reveal private chain-of-thought or hidden deliberation. Give a brief explanation, steps, or checks when they help the user.\n";
    private DBHelper db;
    private static boolean isDownloading = false;
    private final ConversationHistory history = new ConversationHistory();

    /**
     * Creates a Chatbot and seeds training data from one or more raw resource files.
     *
     * @param context   Android context
     * @param rawResIds Zero or more raw resource IDs to seed (e.g. R.raw.chitchat,
     *                  R.raw.chatbot_training_data). Seeding only happens when the
     *                  database is empty. Files are loaded in the order given.
     *                  If no IDs are provided, no automatic seeding is performed.
     */
    public Chatbot(Context context, int... rawResIds) {
        try {
            db = new DBHelper(context);
            seedDatabase(context, rawResIds);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize DBHelper: ", e);
            db = null;
        }
    }

    private void seedDatabase(Context context, int[] rawResIds) {
        if (db == null || rawResIds == null || rawResIds.length == 0) return;
        try {
            Cursor cursor = db.getAll();
            if (cursor != null) {
                if (cursor.getCount() == 0) {
                    Log.d(TAG, "Database empty. Seeding from " + rawResIds.length + " resource file(s)...");
                    for (int resId : rawResIds) {
                        try {
                            InputStream is = context.getResources().openRawResource(resId);
                            importTrainingData(is);
                            Log.d(TAG, "Seeded resource id: " + resId);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to seed resource id: " + resId, e);
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking or seeding database: ", e);
        }
    }

    // =========================
    // MAIN ENTRY (USED BY APP)
    // =========================
    public BotResponse reply(String input) {

        String normalizedInput = normalizeText(input);
        history.add("User: " + normalizedInput);

        // 0. Handle context-aware follow-ups
        if (normalizedInput.equals("why?") || normalizedInput.equals("how?")) {
            return new BotResponse("I base my answers on the knowledge and sources I have access to.", BotResponse.Source.SYSTEM);
        }

        String equationAnswer = solveLinearEquation(input);
        if (equationAnswer != null) {
            BotResponse res = new BotResponse(equationAnswer, BotResponse.Source.LLM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        if (normalizedInput.contains("variable")) {
            String variableExplanation = explainMathConcept(normalizedInput);
            if (variableExplanation != null) {
                BotResponse res = new BotResponse(variableExplanation, BotResponse.Source.LLM);
                history.add("HeroBot: " + res.getText());
                return res;
            }
        }

        // 1. Exact match from DB
        String dbAnswer = null;
        if (db != null) {
            try {
                dbAnswer = db.getAnswer(normalizedInput);
            } catch (Exception e) {
                Log.e(TAG, "DB error getting answer: ", e);
            }
        }
        if (dbAnswer != null) {
            BotResponse res = new BotResponse(dbAnswer, BotResponse.Source.DATABASE);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        // 2. Bilingual built-in responses
        if (normalizedInput.contains("hello") || normalizedInput.contains("hi ") || normalizedInput.contains("hey")) {
            BotResponse res = new BotResponse("Hi! I'm HeroBot.", BotResponse.Source.SYSTEM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        if (normalizedInput.contains("halo") || normalizedInput.contains("hai") || normalizedInput.contains("selamat pagi") || normalizedInput.contains("selamat siang") || normalizedInput.contains("selamat sore") || normalizedInput.contains("selamat malam")) {
            BotResponse res = new BotResponse("Halo! Saya HeroBot, asisten Android Anda.", BotResponse.Source.SYSTEM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        if (normalizedInput.contains("who are you") || normalizedInput.contains("siapa kamu") || normalizedInput.contains("siapa namamu")) {
            BotResponse res = new BotResponse("I am HeroBot, your Android assistant.", BotResponse.Source.SYSTEM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        if (normalizedInput.contains("help") || normalizedInput.contains("bantu") || normalizedInput.contains("tolong")) {
            BotResponse res = new BotResponse("I can answer questions, search the web for info, and help with general topics.", BotResponse.Source.SYSTEM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        // 3. Simple built-in personality
        if (normalizedInput.contains("favorite color") || normalizedInput.contains("warna favorit") || normalizedInput.contains("warna kesukaan")) {
            return new BotResponse("I like Blue, it reminds me of a clean interface. Saya suka biru, karena terasa rapi dan bersih.", BotResponse.Source.SYSTEM);
        }

        // 4. Fallback learning (simple similarity search)
        String smart = findBestMatch(normalizedInput);
        if (smart != null) {
            BotResponse res = new BotResponse(smart, BotResponse.Source.DATABASE);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        boolean webSearchEnabled = isWebSearchEnabled();

        String trigonometricAnswer = evaluateTrigonometricExpression(input);
        if (trigonometricAnswer != null) {
            BotResponse res = new BotResponse(trigonometricAnswer, BotResponse.Source.LLM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        String arithmeticAnswer = evaluateArithmeticExpression(input);
        if (arithmeticAnswer != null) {
            BotResponse res = new BotResponse(arithmeticAnswer, BotResponse.Source.LLM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        String mathConceptAnswer = explainMathConcept(normalizedInput);
        if (mathConceptAnswer != null) {
            BotResponse res = new BotResponse(mathConceptAnswer, BotResponse.Source.LLM);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        // Mathematical questions are solved locally first so web results do not replace reasoning.
        if (isMathematicsRequest(input)) {
            String llmReply = askLocalLLM(input);
            if (llmReply != null) {
                BotResponse res = new BotResponse(llmReply, BotResponse.Source.LLM);
                history.add("HeroBot: " + res.getText());
                return res;
            }
        }

        if (shouldAttemptWebSearch(normalizedInput, webSearchEnabled)) {
            WebSearchResult webResult = fetchFromWebWithSource(normalizedInput);
            if (webResult != null) {
                String cleaned = cleanText(webResult.text);
                String replyText = "According to the web: " + cleaned;
                if ("google".equals(webResult.source)) {
                    replyText += "\nIf you'd like, you can train the chatbot by typing \"train\".";
                }
                BotResponse res = new BotResponse(replyText, BotResponse.Source.WEB);
                history.add("HeroBot: " + res.getText());
                return res;
            }
        }

        // 5. Prefer the local prompt/LLM path for general conversation and as a fallback for web search failures.
        if (shouldPreferLocalPromptBeforeWeb(normalizedInput)) {
            String llmReply = askLocalLLM(normalizedInput);
            if (llmReply != null) {
                BotResponse res = new BotResponse(llmReply, BotResponse.Source.LLM);
                history.add("HeroBot: " + res.getText());
                return res;
            }
        }

        // 6. Internet Search: DuckDuckGo with Google fallback (General fallback)
        WebSearchResult webResult = fetchFromWebWithSource(normalizedInput);
        if (webResult != null) {
            String cleaned = cleanText(webResult.text);
            String replyText = "According to the web: " + cleaned;
            if ("google".equals(webResult.source)) {
                replyText += "\nIf you'd like, you can train the chatbot by typing \"train\".";
            }
            BotResponse res = new BotResponse(replyText, BotResponse.Source.WEB);
            history.add("HeroBot: " + res.getText());
            return res;
        }

        BotResponse res = new BotResponse("I'm not sure how to answer that right now. Please try rephrasing or ask another question.", BotResponse.Source.NONE);
        history.add("HeroBot: " + res.getText());
        return res;
    }

    /**
     * Cleans up text by removing Wikipedia-style citations [1], [2], etc.
     */
    private String cleanText(String text) {
        if (text == null) return null;
        // Remove citation brackets like [1], [12], [citation needed]
        String cleaned = text.replaceAll("\\[\\d+\\]|\\[[^\\]]+\\]", "");
        return cleaned.trim();
    }

    public static boolean shouldAttemptWebSearch(String input, boolean webSearchEnabled) {
        if (!webSearchEnabled || input == null) return false;
        String normalized = normalizeTextStatic(input);
        if (normalized.isEmpty()) return false;

        if (normalized.contains("search the web") || normalized.contains("search web") || normalized.contains("browse the web") || normalized.contains("browse web")) {
            return true;
        }

        if (normalized.contains("look up") || normalized.contains("lookup") || normalized.contains("find me") || normalized.contains("find out")) {
            return true;
        }

        // Prefer local prompt handling for generic informational questions.
        // Use web search only for explicit lookup/search intent.
        //if (normalized.startsWith("what is ") || normalized.startsWith("what are ") || normalized.startsWith("who is ") || normalized.startsWith("who are ")) {
        //    return true;
        //}

        if (normalized.contains("can you tell me about") || normalized.contains("tell me about") || normalized.contains("explain")) {
            return true;
        }

        if (normalized.contains("latest news") || normalized.contains("current weather") || normalized.contains("today") || normalized.contains("now")) {
            return true;
        }

        return false;
    }

    public static boolean isMathematicsRequest(String input) {
        if (input == null) return false;

        String normalized = normalizeTextStatic(input);
        if (normalized.isEmpty()) return false;

        String[] mathematicsTerms = {
                "calculate", "calculation", "compute", "math", "mathematics",
                "solve", "equation", "algebra", "geometry", "trigonometry",
                "derivative", "differentiate", "integral", "integrate", "factor",
                "fraction", "percentage", "probability", "permutation", "combination",
                "log", "logarithm", "sine", "cosine", "tangent", "sin", "cos", "tan",
                "infinity", "infinite", "hitung", "matematika", "menghitung", "persamaan", "turunan", "integral"
        };
        for (String term : mathematicsTerms) {
            if (normalized.contains(term)) return true;
        }

        return input.matches(".*\\d.*(?:[+*/×^=]|[-]).*");
    }

    public static String explainMathConcept(String input) {
        if (input == null) return null;

        String normalized = normalizeTextStatic(input);
        if (normalized.isEmpty()) return null;

        if (normalized.contains("sine") || normalized.contains("sin ") || normalized.contains("sin(")) {
            return "Sine (sin) is the ratio of the opposite side to the hypotenuse in a right triangle. For a 30° angle, sin(30°) = 0.5.";
        }
        if (normalized.contains("cosine") || normalized.contains("cos ") || normalized.contains("cos(")) {
            return "Cosine (cos) is the ratio of the adjacent side to the hypotenuse in a right triangle. For a 60° angle, cos(60°) = 0.5.";
        }
        if (normalized.contains("tangent") || normalized.contains("tan ") || normalized.contains("tan(")) {
            return "Tangent (tan) is the ratio of the opposite side to the adjacent side. For a 45° angle, tan(45°) = 1.";
        }
        if (normalized.contains("infinity") || normalized.contains("infinite")) {
            return "Infinity is a concept meaning a quantity without bound or end. It is not a regular finite number, and in math it is often used to describe limits like 1/x as x approaches 0 or values that grow without stopping.";
        }
        if (normalized.contains("variable")) {
            return "A variable is a symbol, often a letter like x, that represents a value that can change or that we need to find. For example, in 2x + 5 = 15, solving for x gives x = 5.";
        }

        return null;
    }

    public static String solveLinearEquation(String input) {
        if (input == null || input.trim().isEmpty()) return null;

        String equation = input.trim().replaceFirst(
                "(?i)^\\s*(?:please\\s+)?(?:solve\\s+for\\s+[a-z]\\s*:?\\s*|find\\s+[a-z]\\s*:?\\s*|(?:solve|find|calculate|what\\s+is)\\s+)",
                ""
        );
        equation = equation.replace('×', '*');
        String[] sides = equation.split("=", -1);
        if (sides.length != 2) return null;

        try {
            LinearExpressionParser leftParser = new LinearExpressionParser(sides[0]);
            LinearValue left = leftParser.parse();
            LinearExpressionParser rightParser = new LinearExpressionParser(sides[1]);
            LinearValue right = rightParser.parse();

            String variable = leftParser.getVariable();
            if (variable != null && rightParser.getVariable() != null
                    && !variable.equalsIgnoreCase(rightParser.getVariable())) {
                return null;
            }
            if (variable == null) variable = rightParser.getVariable();
            if (variable == null) return null;

            double coefficient = left.coefficient - right.coefficient;
            double constant = right.constant - left.constant;
            if (Math.abs(coefficient) < 1e-12) {
                return Math.abs(constant) < 1e-12
                        ? "Every value of " + variable + " is a solution."
                        : "This equation has no solution.";
            }
            return variable + " = " + formatValue(constant / coefficient);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class LinearValue {
        private final double coefficient;
        private final double constant;

        private LinearValue(double coefficient, double constant) {
            this.coefficient = coefficient;
            this.constant = constant;
        }
    }

    private static final class LinearExpressionParser {
        private final String expression;
        private int position;
        private String variable;

        private LinearExpressionParser(String expression) {
            this.expression = expression;
        }

        private String getVariable() {
            return variable;
        }

        private LinearValue parse() {
            LinearValue value = parseSum();
            skipWhitespace();
            if (position != expression.length()) {
                throw new IllegalArgumentException("Unexpected input");
            }
            return value;
        }

        private LinearValue parseSum() {
            LinearValue value = parseProduct();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    value = add(value, parseProduct(), 1);
                } else if (consume('-')) {
                    value = add(value, parseProduct(), -1);
                } else {
                    return value;
                }
            }
        }

        private LinearValue parseProduct() {
            LinearValue value = parseUnary();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    value = multiply(value, parseUnary());
                } else if (consume('/')) {
                    LinearValue denominator = parseUnary();
                    if (Math.abs(denominator.coefficient) >= 1e-12
                            || Math.abs(denominator.constant) < 1e-12) {
                        throw new IllegalArgumentException("Unsupported division");
                    }
                    value = new LinearValue(
                            value.coefficient / denominator.constant,
                            value.constant / denominator.constant
                    );
                } else if (startsPrimary()) {
                    value = multiply(value, parseUnary());
                } else {
                    return value;
                }
            }
        }

        private LinearValue parseUnary() {
            skipWhitespace();
            if (consume('+')) return parseUnary();
            if (consume('-')) {
                LinearValue value = parseUnary();
                return new LinearValue(-value.coefficient, -value.constant);
            }
            return parsePrimary();
        }

        private LinearValue parsePrimary() {
            skipWhitespace();
            if (consume('(')) {
                LinearValue value = parseSum();
                skipWhitespace();
                if (!consume(')')) throw new IllegalArgumentException("Unclosed parenthesis");
                return value;
            }

            if (position < expression.length()
                    && (Character.isDigit(expression.charAt(position)) || expression.charAt(position) == '.')) {
                return parseNumber();
            }

            if (position < expression.length() && Character.isLetter(expression.charAt(position))) {
                int start = position++;
                while (position < expression.length() && Character.isLetter(expression.charAt(position))) {
                    position++;
                }
                String name = expression.substring(start, position).toLowerCase(Locale.ROOT);
                if (variable != null && !variable.equals(name)) {
                    throw new IllegalArgumentException("Multiple variables");
                }
                variable = name;
                return new LinearValue(1, 0);
            }

            throw new IllegalArgumentException("Expected a number, variable, or parenthesis");
        }

        private LinearValue parseNumber() {
            int start = position;
            boolean hasDigit = false;
            while (position < expression.length()
                    && (Character.isDigit(expression.charAt(position)) || expression.charAt(position) == '.')) {
                if (Character.isDigit(expression.charAt(position))) hasDigit = true;
                position++;
            }
            if (!hasDigit) throw new IllegalArgumentException("Invalid number");
            double value = Double.parseDouble(expression.substring(start, position));
            return new LinearValue(0, value);
        }

        private static LinearValue add(LinearValue left, LinearValue right, int sign) {
            return new LinearValue(
                    left.coefficient + sign * right.coefficient,
                    left.constant + sign * right.constant
            );
        }

        private static LinearValue multiply(LinearValue left, LinearValue right) {
            if (Math.abs(left.coefficient) >= 1e-12 && Math.abs(right.coefficient) >= 1e-12) {
                throw new IllegalArgumentException("Nonlinear expression");
            }
            return new LinearValue(
                    left.coefficient * right.constant + right.coefficient * left.constant,
                    left.constant * right.constant
            );
        }

        private boolean startsPrimary() {
            if (position >= expression.length()) return false;
            char next = expression.charAt(position);
            return Character.isLetterOrDigit(next) || next == '.' || next == '(';
        }

        private boolean consume(char expected) {
            if (position < expression.length() && expression.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
                position++;
            }
        }
    }

    public static String evaluateTrigonometricExpression(String input) {
        if (input == null) return null;

        String normalized = normalizeTextStatic(input);
        if (normalized.isEmpty()) return null;

        String[] trigPatterns = {"sin", "cos", "tan"};
        for (String trig : trigPatterns) {
            if (!normalized.contains(trig)) continue;
            String valuePart = normalized.replace(trig, "").replaceAll("[^0-9.()\\-+/ ]", "").trim();
            if (valuePart.isEmpty()) return null;
            Double degrees = parseDegrees(valuePart);
            if (degrees == null) return null;
            double radians = Math.toRadians(degrees);
            double result;
            if (trig.equals("sin")) result = Math.sin(radians);
            else if (trig.equals("cos")) result = Math.cos(radians);
            else result = Math.tan(radians);
            return trig.toUpperCase() + "(" + degrees + "°) = " + formatValue(result);
        }

        return null;
    }

    private static Double parseDegrees(String value) {
        if (value == null || value.isEmpty()) return null;
        String cleaned = value.replaceAll("[^0-9.\\-+ ]", "").trim();
        if (cleaned.isEmpty()) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatValue(double value) {
        if (Math.abs(value) < 1e-12) return "0";
        if (Math.rint(value) == value) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.6f", value).replaceAll("\\.?0+$", "");
    }

    public static String evaluateArithmeticExpression(String input) {
        if (input == null) return null;

        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        String expression = trimmed;
        expression = expression.replaceAll("(?i)\\bwhat is\\b|\\bcalculate\\b|\\bcompute\\b|\\bcount\\b|\\bsolve\\b|\\bresult\\b|\\bequals\\b", "");
        String displayExpression = expression.replaceAll("\\s+", " ").trim();
        expression = expression.replace('×', '*');
        expression = expression.replaceAll("(?i)\\blogarithm\\b|\\blog\\b", "l");
        expression = expression.replaceAll("\\s+", "");

        if (expression.isEmpty()) return null;

        Matcher percentMatcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*(?:of)?\\s*(\\d+(?:\\.\\d+)?)?").matcher(expression);
        if (percentMatcher.matches()) {
            double value = Double.parseDouble(percentMatcher.group(1));
            double base = percentMatcher.group(2) == null ? 100 : Double.parseDouble(percentMatcher.group(2));
            double result = (value / 100.0) * base;
            return displayExpression + " = " + formatValue(result);
        }

        String cleaned = expression.replaceAll("[^0-9+\\-*/%^().l]", "");
        if (cleaned.isEmpty() || !cleaned.matches(".*\\d.*")) return null;

        try {
            double value = evaluateRpn(toRpn(cleaned));
            if (Double.isNaN(value) || Double.isInfinite(value)) return null;

            return displayExpression + " = " + formatValue(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> toRpn(String expression) {
        List<String> output = new ArrayList<>();
        Deque<String> operators = new ArrayDeque<>();
        StringBuilder number = new StringBuilder();

        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (Character.isDigit(ch) || ch == '.') {
                number.append(ch);
                continue;
            }

            if (number.length() > 0) {
                output.add(number.toString());
                number.setLength(0);
            }

            if (Character.isWhitespace(ch)) continue;

            if (isUnaryOperator(expression, i)) {
                String op = ch == '-' ? "u-" : "u+";
                while (!operators.isEmpty() && !"(".equals(operators.peek()) && precedence(operators.peek()) >= precedence(op)) {
                    output.add(operators.pop());
                }
                operators.push(op);
                continue;
            }

            if (ch == '(') {
                operators.push("(");
                continue;
            }
            if (ch == ')') {
                while (!operators.isEmpty() && !"(".equals(operators.peek())) {
                    output.add(operators.pop());
                }
                if (!operators.isEmpty()) {
                    operators.pop();
                }
                continue;
            }

            String op = String.valueOf(ch);
            while (!operators.isEmpty() && !"(".equals(operators.peek()) && precedence(operators.peek()) >= precedence(op)) {
                output.add(operators.pop());
            }
            operators.push(op);
        }

        if (number.length() > 0) {
            output.add(number.toString());
        }
        while (!operators.isEmpty()) {
            output.add(operators.pop());
        }

        return output;
    }

    private static boolean isUnaryOperator(String expression, int index) {
        char ch = expression.charAt(index);
        if (ch != '+' && ch != '-') return false;
        if (index == 0) return true;

        int previousIndex = index - 1;
        while (previousIndex >= 0 && Character.isWhitespace(expression.charAt(previousIndex))) {
            previousIndex--;
        }
        if (previousIndex < 0) return true;

        char previous = expression.charAt(previousIndex);
        return previous == '(' || previous == '+' || previous == '-' || previous == '*' || previous == '/' || previous == '%' || previous == '^' || previous == 'l';
    }

    private static int precedence(String operator) {
        switch (operator) {
            case "^":
            case "l": return 5;
            case "u-":
            case "u+": return 4;
            case "*":
            case "/":
            case "%": return 3;
            case "+":
            case "-": return 2;
            default: return 0;
        }
    }

    private static double evaluateRpn(List<String> tokens) {
        Deque<Double> stack = new ArrayDeque<>();
        for (String token : tokens) {
            if (token.matches("(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+)") || token.matches("[0-9]+(\\.[0-9]+)?")) {
                stack.push(Double.parseDouble(token));
                continue;
            }
            if (token.equals("(")) continue;
            if ("u-".equals(token)) {
                if (stack.isEmpty()) throw new IllegalArgumentException("Malformed expression");
                stack.push(-stack.pop());
                continue;
            }
            if ("u+".equals(token)) {
                if (stack.isEmpty()) throw new IllegalArgumentException("Malformed expression");
                continue;
            }

            if (stack.size() < 2) {
                throw new IllegalArgumentException("Malformed expression");
            }

            double rhs = stack.pop();
            double lhs = stack.pop();
            switch (token) {
                case "+": stack.push(lhs + rhs); break;
                case "-": stack.push(lhs - rhs); break;
                case "*": stack.push(lhs * rhs); break;
                case "/":
                    if (rhs == 0) throw new IllegalArgumentException("Division by zero");
                    stack.push(lhs / rhs);
                    break;
                case "%":
                    if (rhs == 0) throw new IllegalArgumentException("Division by zero");
                    stack.push(lhs % rhs);
                    break;
                case "^": stack.push(Math.pow(lhs, rhs)); break;
                case "l":
                    if (lhs <= 0 || lhs == 1 || rhs <= 0) {
                        throw new IllegalArgumentException("Invalid logarithm");
                    }
                    stack.push(Math.log(rhs) / Math.log(lhs));
                    break;
                default: throw new IllegalArgumentException("Unsupported operator: " + token);
            }
        }

        if (stack.size() != 1) {
            throw new IllegalArgumentException("Malformed expression");
        }
        return stack.pop();
    }

    public static boolean shouldPreferLocalPromptBeforeWeb(String input) {
        if (input == null) return false;
        String normalized = normalizeTextStatic(input);
        if (normalized.isEmpty()) return false;
        return isMathematicsRequest(input) || !shouldAttemptWebSearch(normalized, true);
    }

    private static String normalizeTextStatic(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}+", "");
        normalized = normalized.replaceAll("[^\\p{L}\\p{N} ]", " ");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isWebSearchEnabled() {
        return db != null ? Boolean.parseBoolean(db.getParameter("web_search_enabled", "true")) : true;
    }

    private String normalizeText(String input) {
        return normalizeTextStatic(input);
    }

    // =========================
    // TRAIN BOT
    // =========================
    public void train(String question, String answer) {
        if (db != null) {
            try {
                db.insertQA(question, answer);
            } catch (Exception e) {
                Log.e(TAG, "DB error inserting QA: ", e);
            }
        }
    }

    public void importTrainingData(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            String question = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("Q: ")) {
                    question = line.substring(3).trim();
                } else if (line.startsWith("A: ") && question != null) {
                    String answer = line.substring(3).trim();
                    db.insertQA(question, answer);
                    question = null;
                }
            }
            Log.d(TAG, "Training data seeded successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to import training data", e);
        }
    }

    // =========================
    // SIMPLE NLP MATCH (NO LIBRARIES)
    // =========================
    private String findBestMatch(String input) {
        if (db == null) return null;
        Cursor cursor = null;
        try {
            cursor = db.getAll();
        } catch (Exception e) {
            Log.e(TAG, "DB error during best match search: ", e);
            return null;
        }
        if (cursor == null) return null;

        List<String> questions = new ArrayList<>();
        List<String> answers = new ArrayList<>();

        try {
            while (cursor.moveToNext()) {
                questions.add(cursor.getString(0));
                answers.add(cursor.getString(1));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading from cursor: ", e);
        } finally {
            cursor.close();
        }

        if (questions.isEmpty()) return null;

        String bestAnswer = null;
        double bestScore = 0;

        Map<String, Integer> inputVector = NLPHelper.getVector(input);

        for (int i = 0; i < questions.size(); i++) {
            Map<String, Integer> questionVector = NLPHelper.getVector(questions.get(i));
            double score = NLPHelper.cosineSimilarity(inputVector, questionVector);

            if (score > bestScore) {
                bestScore = score;
                bestAnswer = answers.get(i);
            }
        }

        // Using a 0.65 threshold for a balance between accuracy and flexibility
        if (bestScore > 0.65) {
            return bestAnswer;
        }

        return null;
    }

    private URL createUrl(String urlString) throws Exception {
        return URI.create(urlString).toURL();
    }

    private String fetchFromWikipedia(String query) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                    URLEncoder.encode(query.replace(" ", "_"), StandardCharsets.UTF_8.name());

            URL url = createUrl(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    JSONObject json = new JSONObject(response.toString());
                    return json.optString("extract", null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Wikipedia Error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private WebSearchResult fetchFromWebWithSource(String query) {
        String wikipediaReply = fetchFromWikipedia(query);
        if (wikipediaReply != null && !wikipediaReply.isEmpty()) {
            return new WebSearchResult(wikipediaReply, "wikipedia");
        }

        String ddgReply = fetchFromDuckDuckGo(query);
        if (ddgReply != null && !ddgReply.isEmpty()) {
            return new WebSearchResult(ddgReply, "duckduckgo");
        }

        String searxngReply = fetchFromSearxng(query);
        if (searxngReply != null && !searxngReply.isEmpty()) {
            return new WebSearchResult(searxngReply, "searxng");
        }

        String googleReply = fetchFromGoogle(query);
        if (googleReply != null && !googleReply.isEmpty()) {
            return new WebSearchResult(googleReply, "google");
        }

        return null;
    }

    private String fetchFromWeb(String query) {
        WebSearchResult webResult = fetchFromWebWithSource(query);
        return webResult != null ? webResult.text : null;
    }

    private static class WebSearchResult {
        private final String text;
        private final String source;

        private WebSearchResult(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    private String fetchFromDuckDuckGo(String query) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://api.duckduckgo.com/?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8) +
                    "&format=json&no_html=1&skip_disambig=1";

            URL url = createUrl(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    JSONObject json = new JSONObject(response.toString());

                    // Priority 1: AbstractText
                    String abstractText = json.optString("AbstractText", "");
                    if (!abstractText.isEmpty()) return abstractText;

                    // Priority 2: Text from first RelatedTopic
                    if (json.has("RelatedTopics")) {
                        org.json.JSONArray topics = json.getJSONArray("RelatedTopics");
                        if (topics.length() > 0) {
                            JSONObject firstTopic = topics.optJSONObject(0);
                            if (firstTopic != null) {
                                return firstTopic.optString("Text", null);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DuckDuckGo Search Error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String fetchFromSearxng(String query) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://searxng.org/search?format=json&q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8);

            URL url = createUrl(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    JSONObject json = new JSONObject(response.toString());
                    if (json.has("results")) {
                        org.json.JSONArray results = json.getJSONArray("results");
                        if (results.length() > 0) {
                            JSONObject first = results.optJSONObject(0);
                            if (first != null) {
                                String content = first.optString("content", "");
                                if (!content.isEmpty()) return content;
                                String title = first.optString("title", "");
                                if (!title.isEmpty()) return title;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SearxNG Search Error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String fetchFromGoogle(String query) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://www.google.com/search?hl=en&num=3&q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8);

            URL url = createUrl(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    String snippet = extractSearchResultSnippet(response.toString());
                    if (snippet != null && !snippet.isEmpty()) {
                        return snippet;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Google Search Error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        try {
            return "See Google search results: " + fetchGoogleSearchUrl(query);
        } catch (Exception e) {
            return "See Google search results: https://www.google.com/search?q=" + query.replace(" ", "+");
        }
    }

    static String fetchGoogleSearchUrl(String query) {
        try {
            return "https://www.google.com/search?hl=en&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "https://www.google.com/search?hl=en&q=" + query.replace(" ", "+");
        }
    }

    static String extractSearchResultSnippet(String html) {
        if (html == null || html.isEmpty()) return null;

        String[] patterns = {
                "(?i)<div[^>]*class=\"[^\"]*(?:BNeawe|Vwi3C|kCrYT)[^\"]*\"[^>]*>(.*?)</div>",
                "(?i)<span[^>]*class=\"[^\"]*(?:BNeawe|Vwi3C|kCrYT)[^\"]*\"[^>]*>(.*?)</span>"
        };

        for (String pattern : patterns) {
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = regex.matcher(html);
            if (matcher.find()) {
                String snippet = matcher.group(1);
                snippet = snippet.replaceAll("<[^>]+>", " ");
                snippet = snippet.replaceAll("\\s+", " ").trim();
                if (!snippet.isEmpty()) {
                    return snippet;
                }
            }
        }

        return null;
    }

    // =========================
    // LLM FALLBACK
    // =========================
    private String askLocalLLM(String prompt) {
        HttpURLConnection conn = null;
        try {
            URL url = createUrl("http://localhost:11434/api/generate");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

                String instruction = CONVERSATIONAL_REASONING_INSTRUCTION
                    + (isMathematicsRequest(prompt)
                    ? "For mathematics, show the relevant formula, necessary calculation steps, units when applicable, and verify the result. Do not use web search or cite web sources.\n"
                    : "");
            StringBuilder fullPrompt = new StringBuilder(instruction);
            for (String h : history.getHistory()) {
                fullPrompt.append(h).append("\n");
            }
            fullPrompt.append("User's current request: ").append(prompt).append("\n");
            fullPrompt.append("HeroBot:");

            String model = db != null ? db.getParameter("model", "llama3.2:1b") : "llama3.2:1b";
            String temperature = db != null ? db.getParameter("temperature", "0.7") : "0.7";
            String topP = db != null ? db.getParameter("top_p", "0.9") : "0.9";
            String stream = db != null ? db.getParameter("stream", "false") : "false";

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", model);
            jsonBody.put("prompt", fullPrompt.toString());
            jsonBody.put("stream", Boolean.parseBoolean(stream));
            jsonBody.put("temperature", Double.parseDouble(temperature));
            jsonBody.put("top_p", Double.parseDouble(topP));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    return jsonResponse.optString("response", "").trim();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "LLM Error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Simple class to manage conversation context for the LLM.
     */
    private static class ConversationHistory {
        private final List<String> logs = new ArrayList<>();
        public void add(String message) { logs.add(message); if (logs.size() > 10) logs.remove(0); }
        public List<String> getHistory() { return logs; }
    }
}