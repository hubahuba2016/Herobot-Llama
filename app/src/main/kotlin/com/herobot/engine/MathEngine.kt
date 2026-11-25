package com.herobot.engine

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Local mathematical solver supporting linear equations, arithmetic (RPN),
 * percentages, logarithms, trigonometry (degrees), and math concept explanations.
 */
object MathEngine {

    private val MATHEMATICS_TERMS = listOf(
        "calculate", "calculation", "compute", "math", "mathematics",
        "solve", "equation", "algebra", "geometry", "trigonometry",
        "derivative", "differentiate", "integral", "integrate", "factor",
        "fraction", "percentage", "probability", "permutation", "combination",
        "log", "logarithm", "sine", "cosine", "tangent", "sin", "cos", "tan",
        "infinity", "infinite", "hitung", "matematika", "menghitung",
        "persamaan", "turunan", "integral"
    )

    private val EQUATION_PREFIX_REGEX = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:solve\\s+for\\s+[a-z]\\s*:?\\s*|find\\s+[a-z]\\s*:?\\s*|(?:solve|find|calculate|what\\s+is)\\s+)"
    )

    private val ARITHMETIC_PREFIX_REGEX = Regex(
        "(?i)\\bwhat is\\b|\\bcalculate\\b|\\bcompute\\b|\\bcount\\b|\\bsolve\\b|\\bresult\\b|\\bequals\\b"
    )

    private val PERCENT_PATTERN = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*(?:of)?\\s*(\\d+(?:\\.\\d+)?)?"
    )

    fun isMathematicsRequest(input: String?): Boolean {
        if (input == null) return false
        val normalized = NLPHelper.normalize(input)
        if (normalized.isEmpty()) return false

        if (MATHEMATICS_TERMS.any { normalized.contains(it) }) {
            return true
        }
        return input.matches(Regex(".*\\d.*(?:[+*/×^=]|[-]).*"))
    }

    fun explainMathConcept(input: String?): String? {
        if (input == null) return null
        val normalized = NLPHelper.normalize(input)
        if (normalized.isEmpty()) return null

        return when {
            normalized.contains("sine") || normalized.contains("sin ") || normalized.contains("sin(") ->
                "Sine (sin) is the ratio of the opposite side to the hypotenuse in a right triangle. For a 30° angle, sin(30°) = 0.5."

            normalized.contains("cosine") || normalized.contains("cos ") || normalized.contains("cos(") ->
                "Cosine (cos) is the ratio of the adjacent side to the hypotenuse in a right triangle. For a 60° angle, cos(60°) = 0.5."

            normalized.contains("tangent") || normalized.contains("tan ") || normalized.contains("tan(") ->
                "Tangent (tan) is the ratio of the opposite side to the adjacent side. For a 45° angle, tan(45°) = 1."

            normalized.contains("infinity") || normalized.contains("infinite") ->
                "Infinity is a concept meaning a quantity without bound or end. It is not a regular finite number, and in math it is often used to describe limits like 1/x as x approaches 0 or values that grow without stopping."

            normalized.contains("variable") ->
                "A variable is a symbol, often a letter like x, that represents a value that can change or that we need to find. For example, in 2x + 5 = 15, solving for x gives x = 5."

            else -> null
        }
    }

    fun solveLinearEquation(input: String?): String? {
        if (input.isNullOrBlank()) return null

        var equation = EQUATION_PREFIX_REGEX.replaceFirst(input.trim(), "")
        equation = equation.replace('×', '*')
        val sides = equation.split("=", limit = 0)
        if (sides.size != 2) return null

        return try {
            val leftParser = LinearExpressionParser(sides[0])
            val left = leftParser.parse()
            val rightParser = LinearExpressionParser(sides[1])
            val right = rightParser.parse()

            var variable = leftParser.variable
            if (variable != null && rightParser.variable != null &&
                !variable.equals(rightParser.variable, ignoreCase = true)
            ) {
                return null
            }
            if (variable == null) variable = rightParser.variable
            if (variable == null) return null

            val coefficient = left.coefficient - right.coefficient
            val constant = right.constant - left.constant
            if (abs(coefficient) < 1e-12) {
                if (abs(constant) < 1e-12) {
                    "Every value of $variable is a solution."
                } else {
                    "This equation has no solution."
                }
            } else {
                "$variable = ${formatValue(constant / coefficient)}"
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun evaluateTrigonometricExpression(input: String?): String? {
        if (input == null) return null
        val normalized = NLPHelper.normalize(input)
        if (normalized.isEmpty()) return null

        val trigPatterns = listOf("sin", "cos", "tan")
        for (trig in trigPatterns) {
            if (!normalized.contains(trig)) continue
            val valuePart = normalized.replace(trig, "")
                .replace(Regex("[^0-9.()\\-+/ ]"), "")
                .trim()
            if (valuePart.isEmpty()) return null
            val degrees = parseDegrees(valuePart) ?: return null
            val radians = Math.toRadians(degrees)
            val result = when (trig) {
                "sin" -> sin(radians)
                "cos" -> cos(radians)
                else -> tan(radians)
            }
            return "${trig.uppercase(Locale.US)}($degrees°) = ${formatValue(result)}"
        }
        return null
    }

    fun evaluateArithmeticExpression(input: String?): String? {
        if (input == null) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        var expression = ARITHMETIC_PREFIX_REGEX.replace(trimmed, "")
        val displayExpression = expression.replace(Regex("\\s+"), " ").trim()
        expression = expression.replace('×', '*')
        expression = expression.replace(Regex("(?i)\\blogarithm\\b|\\blog\\b"), "l")
        expression = expression.replace(Regex("\\s+"), "")

        if (expression.isEmpty()) return null

        val percentMatcher = PERCENT_PATTERN.matcher(expression)
        if (percentMatcher.matches()) {
            val value = percentMatcher.group(1)?.toDoubleOrNull() ?: return null
            val base = percentMatcher.group(2)?.toDoubleOrNull() ?: 100.0
            val result = (value / 100.0) * base
            return "$displayExpression = ${formatValue(result)}"
        }

        val cleaned = expression.replace(Regex("[^0-9+\\-*/%^().l]"), "")
        if (cleaned.isEmpty() || !cleaned.matches(Regex(".*\\d.*"))) return null

        return try {
            val value = evaluateRpn(toRpn(cleaned))
            if (value.isNaN() || value.isInfinite()) return null
            "$displayExpression = ${formatValue(value)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDegrees(value: String?): Double? {
        if (value.isNullOrEmpty()) return null
        val cleaned = value.replace(Regex("[^0-9.\\-+ ]"), "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    private fun formatValue(value: Double): String {
        if (abs(value) < 1e-12) return "0"
        if (java.lang.Math.rint(value) == value) return String.format(Locale.US, "%.0f", value)
        return String.format(Locale.US, "%.6f", value).replace(Regex("\\.?0+$"), "")
    }

    private fun toRpn(expression: String): List<String> {
        val output = ArrayList<String>()
        val operators = ArrayDeque<String>()
        val number = StringBuilder()

        for (i in expression.indices) {
            val ch = expression[i]
            if (ch.isDigit() || ch == '.') {
                number.append(ch)
                continue
            }

            if (number.isNotEmpty()) {
                output.add(number.toString())
                number.setLength(0)
            }

            if (ch.isWhitespace()) continue

            if (isUnaryOperator(expression, i)) {
                val op = if (ch == '-') "u-" else "u+"
                while (operators.isNotEmpty() && operators.last() != "(" &&
                    precedence(operators.last()) >= precedence(op)
                ) {
                    output.add(operators.removeLast())
                }
                operators.addLast(op)
                continue
            }

            if (ch == '(') {
                operators.addLast("(")
                continue
            }

            if (ch == ')') {
                while (operators.isNotEmpty() && operators.last() != "(") {
                    output.add(operators.removeLast())
                }
                if (operators.isNotEmpty()) {
                    operators.removeLast()
                }
                continue
            }

            val op = ch.toString()
            while (operators.isNotEmpty() && operators.last() != "(" &&
                precedence(operators.last()) >= precedence(op)
            ) {
                output.add(operators.removeLast())
            }
            operators.addLast(op)
        }

        if (number.isNotEmpty()) {
            output.add(number.toString())
        }
        while (operators.isNotEmpty()) {
            output.add(operators.removeLast())
        }

        return output
    }

    private fun isUnaryOperator(expression: String, index: Int): Boolean {
        val ch = expression[index]
        if (ch != '+' && ch != '-') return false
        if (index == 0) return true

        var previousIndex = index - 1
        while (previousIndex >= 0 && expression[previousIndex].isWhitespace()) {
            previousIndex--
        }
        if (previousIndex < 0) return true

        val previous = expression[previousIndex]
        return previous == '(' || previous == '+' || previous == '-' ||
            previous == '*' || previous == '/' || previous == '%' ||
            previous == '^' || previous == 'l'
    }

    private fun precedence(operator: String): Int = when (operator) {
        "^", "l" -> 5
        "u-", "u+" -> 4
        "*", "/", "%" -> 3
        "+", "-" -> 2
        else -> 0
    }

    private fun evaluateRpn(tokens: List<String>): Double {
        val stack = ArrayDeque<Double>()
        val numberRegex = Regex("(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+)")
        for (token in tokens) {
            if (token.matches(numberRegex)) {
                stack.addLast(token.toDouble())
                continue
            }
            if (token == "(") continue
            if (token == "u-") {
                require(stack.isNotEmpty()) { "Malformed expression" }
                stack.addLast(-stack.removeLast())
                continue
            }
            if (token == "u+") {
                require(stack.isNotEmpty()) { "Malformed expression" }
                continue
            }

            require(stack.size >= 2) { "Malformed expression" }
            val rhs = stack.removeLast()
            val lhs = stack.removeLast()
            when (token) {
                "+" -> stack.addLast(lhs + rhs)
                "-" -> stack.addLast(lhs - rhs)
                "*" -> stack.addLast(lhs * rhs)
                "/" -> {
                    require(rhs != 0.0) { "Division by zero" }
                    stack.addLast(lhs / rhs)
                }
                "%" -> {
                    require(rhs != 0.0) { "Division by zero" }
                    stack.addLast(lhs % rhs)
                }
                "^" -> stack.addLast(lhs.pow(rhs))
                "l" -> {
                    require(lhs > 0 && lhs != 1.0 && rhs > 0) { "Invalid logarithm" }
                    stack.addLast(ln(rhs) / ln(lhs))
                }
                else -> throw IllegalArgumentException("Unsupported operator: $token")
            }
        }

        require(stack.size == 1) { "Malformed expression" }
        return stack.removeLast()
    }

    private data class LinearValue(val coefficient: Double, val constant: Double)

    private class LinearExpressionParser(private val expression: String) {
        private var position = 0
        var variable: String? = null
            private set

        fun parse(): LinearValue {
            val value = parseSum()
            skipWhitespace()
            require(position == expression.length) { "Unexpected input" }
            return value
        }

        private fun parseSum(): LinearValue {
            var value = parseProduct()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('+') -> add(value, parseProduct(), 1)
                    consume('-') -> add(value, parseProduct(), -1)
                    else -> return value
                }
            }
        }

        private fun parseProduct(): LinearValue {
            var value = parseUnary()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('*') -> multiply(value, parseUnary())
                    consume('/') -> {
                        val denominator = parseUnary()
                        require(abs(denominator.coefficient) < 1e-12 && abs(denominator.constant) >= 1e-12) {
                            "Unsupported division"
                        }
                        LinearValue(
                            value.coefficient / denominator.constant,
                            value.constant / denominator.constant
                        )
                    }
                    startsPrimary() -> multiply(value, parseUnary())
                    else -> return value
                }
            }
        }

        private fun parseUnary(): LinearValue {
            skipWhitespace()
            if (consume('+')) return parseUnary()
            if (consume('-')) {
                val value = parseUnary()
                return LinearValue(-value.coefficient, -value.constant)
            }
            return parsePrimary()
        }

        private fun parsePrimary(): LinearValue {
            skipWhitespace()
            if (consume('(')) {
                val value = parseSum()
                skipWhitespace()
                require(consume(')')) { "Unclosed parenthesis" }
                return value
            }

            if (position < expression.length &&
                (expression[position].isDigit() || expression[position] == '.')
            ) {
                return parseNumber()
            }

            if (position < expression.length && expression[position].isLetter()) {
                val start = position++
                while (position < expression.length && expression[position].isLetter()) {
                    position++
                }
                val name = expression.substring(start, position).lowercase(Locale.ROOT)
                require(variable == null || variable == name) { "Multiple variables" }
                variable = name
                return LinearValue(1.0, 0.0)
            }

            throw IllegalArgumentException("Expected a number, variable, or parenthesis")
        }

        private fun parseNumber(): LinearValue {
            val start = position
            var hasDigit = false
            while (position < expression.length &&
                (expression[position].isDigit() || expression[position] == '.')
            ) {
                if (expression[position].isDigit()) hasDigit = true
                position++
            }
            require(hasDigit) { "Invalid number" }
            val value = expression.substring(start, position).toDouble()
            return LinearValue(0.0, value)
        }

        private fun startsPrimary(): Boolean {
            if (position >= expression.length) return false
            val next = expression[position]
            return next.isLetterOrDigit() || next == '.' || next == '('
        }

        private fun consume(expected: Char): Boolean {
            if (position < expression.length && expression[position] == expected) {
                position++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (position < expression.length && expression[position].isWhitespace()) {
                position++
            }
        }

        private companion object {
            fun add(left: LinearValue, right: LinearValue, sign: Int): LinearValue =
                LinearValue(
                    left.coefficient + sign * right.coefficient,
                    left.constant + sign * right.constant
                )

            fun multiply(left: LinearValue, right: LinearValue): LinearValue {
                require(!(abs(left.coefficient) >= 1e-12 && abs(right.coefficient) >= 1e-12)) {
                    "Nonlinear expression"
                }
                return LinearValue(
                    left.coefficient * right.constant + right.coefficient * left.constant,
                    left.constant * right.constant
                )
            }
        }
    }
}
