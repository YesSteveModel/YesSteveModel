package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.program.ProgramNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

final class MolangSourceParser {
    private final MolangFrontend.SourceLocation location;
    private final MolangFrontend.Limits limits;
    private final Lexer lexer;
    private final TreeSet<MolangFrontend.ReferencedSymbol> references =
        new TreeSet<>(MolangFrontend.ReferencedSymbol.ORDER);
    private final HashSet<String> locals = new HashSet<>();

    private Token current;
    private int tokenCount;
    private int nodeCount;
    private int depth;
    private int maximumDepth;

    MolangSourceParser(String source, MolangFrontend.SourceLocation location, MolangFrontend.Limits limits)
        throws MolangFrontendException {
        this.location = Objects.requireNonNull(location, "location");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.lexer = new Lexer(source, location);
        advance();
    }

    Result parse(MolangFrontend.SourceMode mode) throws MolangFrontendException {
        try {
            return parseBounded(mode);
        } catch (LimitSignal limit) {
            throw failure(MolangFrontendException.Kind.SOURCE_LIMIT, current.offset(), limit.getMessage(), limit);
        }
    }

    private Result parseBounded(MolangFrontend.SourceMode mode) throws MolangFrontendException {
        var roots = new ArrayList<ProgramNode>();
        while (current.kind() != Kind.EOF) {
            if (accept(Kind.SEMICOLON)) {
                continue;
            }
            if (roots.size() == limits.maxStatements()) {
                fail(MolangFrontendException.Kind.SOURCE_LIMIT,
                    "root statement count exceeds " + limits.maxStatements());
            }
            roots.add(parseExpression(0));
            if (current.kind() != Kind.EOF && !accept(Kind.SEMICOLON)) {
                fail(MolangFrontendException.Kind.SYNTAX, "expected ';' or end of source");
            }
        }
        if (roots.isEmpty()) {
            roots.add(node(new ProgramNode.Unset()));
        }
        return new Result(List.copyOf(roots), List.copyOf(references), tokenCount, nodeCount, maximumDepth,
            locals.size());
    }

    private ProgramNode parseExpression(int minimumPrecedence) throws MolangFrontendException {
        enter();
        try {
            var left = parsePrefix();
            while (true) {
                if (current.kind() == Kind.QUESTION && PREC_CONDITIONAL >= minimumPrecedence) {
                    advance();
                    var ifTrue = parseExpression(0);
                    var ifFalse =
                        accept(Kind.COLON) ? parseExpression(PREC_CONDITIONAL) : node(new ProgramNode.Unset());
                    left = node(new ProgramNode.Conditional(left, ifTrue, ifFalse));
                    continue;
                }
                var operator = binary(current.kind());
                if (operator == null || operator.precedence() < minimumPrecedence) {
                    return left;
                }
                advance();
                var nextMinimum = operator.rightAssociative() ? operator.precedence() : operator.precedence() + 1;
                var right = parseExpression(nextMinimum);
                left = switch (operator.kind()) {
                    case COALESCE -> node(new ProgramNode.Coalesce(left, right));
                    case ASSIGN -> assignment(left, right);
                    case ARROW -> node(new ProgramNode.WithContext(left, right));
                    default -> node(new ProgramNode.Binary(operator.binary(), left, right));
                };
            }
        } finally {
            depth--;
        }
    }

    private ProgramNode parsePrefix() throws MolangFrontendException {
        return switch (current.kind()) {
            case FLOAT -> {
                var token = current;
                advance();
                try {
                    yield node(ProgramNode.F32.of(Float.parseFloat(token.text())));
                } catch (NumberFormatException failure) {
                    throw failure(MolangFrontendException.Kind.SYNTAX, token.offset(), "invalid numeric literal",
                        failure);
                }
            }
            case STRING -> {
                var value = current.text();
                advance();
                yield node(new ProgramNode.StringLiteral(value));
            }
            case TRUE -> {
                advance();
                yield node(new ProgramNode.Bool(true));
            }
            case FALSE -> {
                advance();
                yield node(new ProgramNode.Bool(false));
            }
            case IDENTIFIER -> parseIdentifier();
            case LPAREN -> {
                advance();
                var expression = parseExpression(0);
                require(Kind.RPAREN, "expected ')'");
                advance();
                yield parsePostfix(expression);
            }
            case LBRACE -> parsePostfix(parseBlock());
            case PLUS -> unary(ProgramNode.UnaryOperator.POSITIVE);
            case MINUS -> unary(ProgramNode.UnaryOperator.NEGATE);
            case BANG -> unary(ProgramNode.UnaryOperator.LOGICAL_NOT);
            case RETURN -> parseReturn();
            case BREAK -> {
                advance();
                yield node(new ProgramNode.Control(ProgramNode.ControlOperator.BREAK, java.util.Optional.empty()));
            }
            case CONTINUE -> {
                advance();
                yield node(new ProgramNode.Control(ProgramNode.ControlOperator.CONTINUE, java.util.Optional.empty()));
            }
            default ->
                throw failure(MolangFrontendException.Kind.SYNTAX, current.offset(), "expected expression", null);
        };
    }

    private ProgramNode parseIdentifier() throws MolangFrontendException {
        var parts = new ArrayList<String>();
        parts.add(MolangNames.identifier(current.text()));
        advance();
        while (accept(Kind.DOT)) {
            require(Kind.IDENTIFIER, "expected identifier after '.'");
            parts.add(MolangNames.identifier(current.text()));
            advance();
        }
        ProgramNode value;
        if (accept(Kind.LPAREN)) {
            var arguments = parseArguments();
            value = lowerCall(parts, arguments);
        } else {
            value = lowerRead(parts);
        }
        return parsePostfix(value);
    }

    private ProgramNode parsePostfix(ProgramNode value) throws MolangFrontendException {
        while (true) {
            if (accept(Kind.DOT)) {
                require(Kind.IDENTIFIER, "expected identifier after '.'");
                var member = MolangNames.identifier(current.text());
                advance();
                value = node(new ProgramNode.Member(value, member));
                continue;
            }
            if (accept(Kind.LBRACKET)) {
                var index = parseExpression(0);
                require(Kind.RBRACKET, "expected ']'");
                advance();
                value = node(new ProgramNode.Index(value, index));
                continue;
            }
            if (current.kind() == Kind.LPAREN) {
                fail(MolangFrontendException.Kind.UNSUPPORTED, "only named symbols may be invoked");
            }
            return value;
        }
    }

    private List<ProgramNode> parseArguments() throws MolangFrontendException {
        var arguments = new ArrayList<ProgramNode>();
        if (accept(Kind.RPAREN)) {
            return List.of();
        }
        while (true) {
            if (arguments.size() == limits.maxArguments()) {
                fail(MolangFrontendException.Kind.SOURCE_LIMIT, "argument count exceeds " + limits.maxArguments());
            }
            arguments.add(parseExpression(0));
            if (accept(Kind.RPAREN)) {
                return List.copyOf(arguments);
            }
            require(Kind.COMMA, "expected ',' between arguments");
            advance();
        }
    }

    private ProgramNode parseBlock() throws MolangFrontendException {
        require(Kind.LBRACE, "expected '{'");
        advance();
        var children = new ArrayList<ProgramNode>();
        while (current.kind() != Kind.RBRACE) {
            if (current.kind() == Kind.EOF) {
                fail(MolangFrontendException.Kind.SYNTAX, "unclosed block");
            }
            if (accept(Kind.SEMICOLON)) {
                continue;
            }
            if (children.size() == limits.maxStatements()) {
                fail(MolangFrontendException.Kind.SOURCE_LIMIT,
                    "block statement count exceeds " + limits.maxStatements());
            }
            children.add(parseExpression(0));
            if (current.kind() != Kind.RBRACE && !accept(Kind.SEMICOLON)) {
                fail(MolangFrontendException.Kind.SYNTAX, "expected ';' or '}'");
            }
        }
        advance();
        return node(new ProgramNode.Block(children));
    }

    private ProgramNode parseReturn() throws MolangFrontendException {
        advance();
        var value = current.kind() == Kind.SEMICOLON || current.kind() == Kind.RBRACE || current.kind() == Kind.EOF
            ? java.util.Optional.<ProgramNode>empty() : java.util.Optional.of(parseExpression(0));
        return node(new ProgramNode.Control(ProgramNode.ControlOperator.RETURN, value));
    }

    private ProgramNode unary(ProgramNode.UnaryOperator operator) throws MolangFrontendException {
        advance();
        return node(new ProgramNode.Unary(operator, parseExpression(PREC_PREFIX)));
    }

    private ProgramNode lowerRead(List<String> parts) throws MolangFrontendException {
        var root = parts.get(0);
        if (root.equals("null") && parts.size() == 1) {
            return node(new ProgramNode.Unset());
        }
        if ((root.equals("roaming") || root.equals("r")) && parts.size() == 2) {
            return symbol(ProgramNode.SymbolDomain.INSTANCE,
                com.elfmcys.ysm.api.molang.MolangNames.roamingSlot(parts.get(1)), ProgramNode.AccessForm.READ,
                List.of());
        }
        if ((root.equals("v") || root.equals("variable")) && parts.size() >= 2) {
            if (parts.get(1).equals("roaming") && parts.size() == 3) {
                return symbol(ProgramNode.SymbolDomain.INSTANCE,
                    com.elfmcys.ysm.api.molang.MolangNames.roamingSlot(parts.get(2)), ProgramNode.AccessForm.READ,
                    List.of());
            }
            if (com.elfmcys.ysm.api.molang.MolangNames.isReservedRoamingSlot(parts.get(1))) {
                fail(MolangFrontendException.Kind.UNSUPPORTED,
                    "variable name uses the reserved roaming storage prefix");
            }
            var value = symbol(ProgramNode.SymbolDomain.INSTANCE, parts.get(1), ProgramNode.AccessForm.READ, List.of());
            return members(value, parts, 2);
        }
        if (root.equals("math") && parts.size() == 2 && parts.get(1).equals("pi")) {
            return node(ProgramNode.F32.of((float)java.lang.Math.PI));
        }
        if ((root.equals("t") || root.equals("temp")) && parts.size() >= 2) {
            addLocal(parts.get(1));
            var value = symbol(ProgramNode.SymbolDomain.LOCAL, parts.get(1), ProgramNode.AccessForm.READ, List.of());
            return members(value, parts, 2);
        }
        if (root.equals("args")) {
            addLocal("args");
            return members(symbol(ProgramNode.SymbolDomain.LOCAL, "args", ProgramNode.AccessForm.READ, List.of()),
                parts, 1);
        }
        if (root.equals("this") && parts.size() == 1) {
            addLocal("_this");
            return symbol(ProgramNode.SymbolDomain.LOCAL, "_this", ProgramNode.AccessForm.READ, List.of());
        }
        if (root.equals("fn") && parts.size() >= 2) {
            var value = symbol(ProgramNode.SymbolDomain.USER_FUNCTION, MolangNames.userFunctionSymbol(parts.get(1)),
                ProgramNode.AccessForm.READ, List.of());
            return members(value, parts, 2);
        }
        var external = external(parts);
        return members(symbol(ProgramNode.SymbolDomain.EXTERNAL, external, ProgramNode.AccessForm.READ, List.of()),
            parts, Math.min(2, parts.size()));
    }

    private ProgramNode lowerCall(List<String> parts, List<ProgramNode> arguments) throws MolangFrontendException {
        var root = parts.get(0);
        if (parts.size() == 1 && root.equals("loop")) {
            if (arguments.size() != 2) {
                fail(MolangFrontendException.Kind.SYNTAX, "loop requires two arguments");
            }
            return node(new ProgramNode.Loop(arguments.get(0), arguments.get(1)));
        }
        if (parts.size() == 1 && root.equals("for_each")) {
            if (arguments.size() != 3 || !(arguments.get(0) instanceof ProgramNode.Symbol local)
                || local.ref().domain() != ProgramNode.SymbolDomain.LOCAL
                || local.accessForm() != ProgramNode.AccessForm.READ) {
                fail(MolangFrontendException.Kind.SYNTAX, "for_each requires a direct t.* local as its first argument");
            }
            var local = (ProgramNode.Symbol)arguments.get(0);
            return node(new ProgramNode.ForEach(local.ref(), arguments.get(1), arguments.get(2)));
        }
        if (root.equals("fn") && parts.size() == 2) {
            return symbol(ProgramNode.SymbolDomain.USER_FUNCTION, MolangNames.userFunctionSymbol(parts.get(1)),
                ProgramNode.AccessForm.INVOKE, arguments);
        }
        if ((root.equals("v") || root.equals("variable")) && parts.size() == 2) {
            return symbol(ProgramNode.SymbolDomain.INSTANCE, parts.get(1), ProgramNode.AccessForm.INVOKE, arguments);
        }
        if ((root.equals("t") || root.equals("temp")) && parts.size() == 2) {
            addLocal(parts.get(1));
            return symbol(ProgramNode.SymbolDomain.LOCAL, parts.get(1), ProgramNode.AccessForm.INVOKE, arguments);
        }
        if (parts.size() > 2) {
            fail(MolangFrontendException.Kind.UNSUPPORTED,
                "invocation through a struct member is not representable in Portable Program V1");
        }
        return symbol(ProgramNode.SymbolDomain.EXTERNAL, external(parts), ProgramNode.AccessForm.INVOKE, arguments);
    }

    private ProgramNode members(ProgramNode value, List<String> parts, int first) {
        var result = value;
        for (var index = first; index < parts.size(); index++) {
            result = node(new ProgramNode.Member(result, parts.get(index)));
        }
        return result;
    }

    private String external(List<String> parts) {
        var root = parts.get(0);
        var member = parts.size() == 1 ? root : parts.get(1);
        if (parts.size() == 1) {
            return "global:" + member;
        }
        var namespace = switch (root) {
            case "q", "query" -> "query";
            case "c", "context" -> "ysm.context";
            case "math" -> "math";
            case "ysm", "ctrl" -> root;
            default -> root;
        };
        return namespace + ':' + member;
    }

    private ProgramNode symbol(ProgramNode.SymbolDomain domain, String name, ProgramNode.AccessForm accessForm,
        List<ProgramNode> arguments) {
        references.add(new MolangFrontend.ReferencedSymbol(domain, name, accessForm));
        return node(new ProgramNode.Symbol(new ProgramNode.SymbolRef(domain, name), accessForm, arguments));
    }

    private ProgramNode assignment(ProgramNode target, ProgramNode value) throws MolangFrontendException {
        if (target instanceof ProgramNode.Symbol symbol && symbol.accessForm() == ProgramNode.AccessForm.READ
            || target instanceof ProgramNode.Member || target instanceof ProgramNode.Index) {
            return node(new ProgramNode.Assignment(target, value));
        }
        fail(MolangFrontendException.Kind.SYNTAX, "invalid assignment target");
        throw new AssertionError();
    }

    private void addLocal(String name) throws MolangFrontendException {
        locals.add(name);
        if (locals.size() > limits.maxLocals()) {
            fail(MolangFrontendException.Kind.SOURCE_LIMIT, "local count exceeds " + limits.maxLocals());
        }
    }

    private <T extends ProgramNode> T node(T value) {
        if (++nodeCount > limits.maxNodes()) {
            throw new LimitSignal("node count exceeds " + limits.maxNodes());
        }
        return value;
    }

    private void enter() throws MolangFrontendException {
        depth++;
        maximumDepth = Math.max(maximumDepth, depth);
        if (depth > limits.maxDepth()) {
            fail(MolangFrontendException.Kind.SOURCE_LIMIT, "source nesting exceeds " + limits.maxDepth());
        }
    }

    private void advance() throws MolangFrontendException {
        try {
            current = lexer.next();
        } catch (LimitSignal limit) {
            throw failure(MolangFrontendException.Kind.SOURCE_LIMIT, lexer.offset(), limit.getMessage(), limit);
        }
        if (++tokenCount > limits.maxTokens()) {
            throw failure(MolangFrontendException.Kind.TOKEN_LIMIT, current.offset(),
                "token count exceeds " + limits.maxTokens(), null);
        }
    }

    private boolean accept(Kind kind) throws MolangFrontendException {
        if (current.kind() != kind) {
            return false;
        }
        advance();
        return true;
    }

    private void require(Kind kind, String message) throws MolangFrontendException {
        if (current.kind() != kind) {
            fail(MolangFrontendException.Kind.SYNTAX, message);
        }
    }

    private void fail(MolangFrontendException.Kind kind, String message) throws MolangFrontendException {
        throw failure(kind, current.offset(), message, null);
    }

    private MolangFrontendException failure(MolangFrontendException.Kind kind, int offset, String message,
        Throwable cause) {
        return new MolangFrontendException(kind, location, offset, message, cause);
    }

    private static Operator binary(Kind kind) {
        return switch (kind) {
            case EQ -> new Operator(OpKind.ASSIGN, null, 1, true);
            case ARROW -> new Operator(OpKind.ARROW, null, 2, true);
            case QUESTION_QUESTION -> new Operator(OpKind.COALESCE, null, 4, true);
            case OR_OR -> operator(ProgramNode.BinaryOperator.OR, 5);
            case AND_AND -> operator(ProgramNode.BinaryOperator.AND, 6);
            case EQ_EQ -> operator(ProgramNode.BinaryOperator.EQ, 7);
            case BANG_EQ -> operator(ProgramNode.BinaryOperator.NEQ, 7);
            case LT -> operator(ProgramNode.BinaryOperator.LT, 8);
            case LTE -> operator(ProgramNode.BinaryOperator.LTE, 8);
            case GT -> operator(ProgramNode.BinaryOperator.GT, 8);
            case GTE -> operator(ProgramNode.BinaryOperator.GTE, 8);
            case PLUS -> operator(ProgramNode.BinaryOperator.ADD, 9);
            case MINUS -> operator(ProgramNode.BinaryOperator.SUB, 9);
            case STAR -> operator(ProgramNode.BinaryOperator.MUL, 10);
            case SLASH -> operator(ProgramNode.BinaryOperator.DIV, 10);
            default -> null;
        };
    }

    private static Operator operator(ProgramNode.BinaryOperator operator, int precedence) {
        return new Operator(OpKind.BINARY, operator, precedence, false);
    }

    private static final int PREC_CONDITIONAL = 3;
    private static final int PREC_PREFIX = 11;

    private enum OpKind {
        BINARY, COALESCE, ASSIGN, ARROW
    }

    private record Operator(OpKind kind, ProgramNode.BinaryOperator binary, int precedence, boolean rightAssociative) {
    }

    record Result(List<ProgramNode> roots, List<MolangFrontend.ReferencedSymbol> references, int tokenCount,
        int nodeCount, int maximumDepth, int localCount) {
    }

    private enum Kind {
        EOF, IDENTIFIER, FLOAT, STRING, TRUE, FALSE, BREAK, CONTINUE, RETURN, DOT, BANG, AND_AND, OR_OR, LT, LTE, GT,
        GTE, EQ, EQ_EQ, BANG_EQ, STAR, SLASH, PLUS, MINUS, LPAREN, RPAREN, LBRACE, RBRACE, QUESTION_QUESTION, QUESTION,
        COLON, ARROW, LBRACKET, RBRACKET, COMMA, SEMICOLON
    }

    private record Token(Kind kind, String text, int offset) {
    }

    private static final class Lexer {
        private final String source;
        private final MolangFrontend.SourceLocation location;
        private int offset;

        private Lexer(String source, MolangFrontend.SourceLocation location) {
            this.source = source;
            this.location = location;
        }

        private int offset() {
            return offset;
        }

        private Token next() throws MolangFrontendException {
            skipIgnored();
            if (offset == source.length()) {
                return token(Kind.EOF, null, offset);
            }
            var start = offset;
            var current = source.charAt(offset++);
            if (asciiLetter(current) || current == '_') {
                while (offset < source.length()) {
                    var value = source.charAt(offset);
                    if (!asciiLetter(value) && !Character.isDigit(value) && value != '_') {
                        break;
                    }
                    offset++;
                }
                var word = source.substring(start, offset).toLowerCase(Locale.ROOT);
                return token(switch (word) {
                    case "true" -> Kind.TRUE;
                    case "false" -> Kind.FALSE;
                    case "break" -> Kind.BREAK;
                    case "continue" -> Kind.CONTINUE;
                    case "return" -> Kind.RETURN;
                    default -> Kind.IDENTIFIER;
                }, word, start);
            }
            if (Character.isDigit(current)
                || current == '.' && offset < source.length() && Character.isDigit(source.charAt(offset))) {
                return number(start, current);
            }
            if (current == '\'') {
                return string(start);
            }
            return switch (current) {
                case '.' -> token(Kind.DOT, null, start);
                case '!' -> match('=') ? token(Kind.BANG_EQ, null, start) : token(Kind.BANG, null, start);
                case '&' -> requiredPair('&', Kind.AND_AND, start);
                case '|' -> requiredPair('|', Kind.OR_OR, start);
                case '<' -> match('=') ? token(Kind.LTE, null, start) : token(Kind.LT, null, start);
                case '>' -> match('=') ? token(Kind.GTE, null, start) : token(Kind.GT, null, start);
                case '=' -> match('=') ? token(Kind.EQ_EQ, null, start) : token(Kind.EQ, null, start);
                case '-' -> match('>') ? token(Kind.ARROW, null, start) : token(Kind.MINUS, null, start);
                case '?' -> match('?') ? token(Kind.QUESTION_QUESTION, null, start) : token(Kind.QUESTION, null, start);
                case '*' -> token(Kind.STAR, null, start);
                case '/' -> token(Kind.SLASH, null, start);
                case '+' -> token(Kind.PLUS, null, start);
                case '(' -> token(Kind.LPAREN, null, start);
                case ')' -> token(Kind.RPAREN, null, start);
                case '{' -> token(Kind.LBRACE, null, start);
                case '}' -> token(Kind.RBRACE, null, start);
                case ':' -> token(Kind.COLON, null, start);
                case '[' -> token(Kind.LBRACKET, null, start);
                case ']' -> token(Kind.RBRACKET, null, start);
                case ',' -> token(Kind.COMMA, null, start);
                case ';' -> token(Kind.SEMICOLON, null, start);
                default -> throw new MolangFrontendException(MolangFrontendException.Kind.SYNTAX, location, start,
                    "invalid character '" + current + "'");
            };
        }

        private void skipIgnored() {
            while (offset < source.length()) {
                var current = source.charAt(offset);
                if (Character.isWhitespace(current)) {
                    offset++;
                    continue;
                }
                if (current == '/' && offset + 1 < source.length() && source.charAt(offset + 1) == '/') {
                    offset += 2;
                    while (offset < source.length() && source.charAt(offset) != '\n' && source.charAt(offset) != '\r') {
                        offset++;
                    }
                    continue;
                }
                return;
            }
        }

        private Token number(int start, char first) throws MolangFrontendException {
            var sawDot = first == '.';
            while (offset < source.length()) {
                var current = source.charAt(offset);
                if (Character.isDigit(current)) {
                    offset++;
                } else if (current == '.' && !sawDot) {
                    sawDot = true;
                    offset++;
                } else {
                    break;
                }
            }
            if (offset < source.length() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
                offset++;
                if (offset < source.length() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
                    offset++;
                }
                var exponentStart = offset;
                while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                    offset++;
                }
                if (exponentStart == offset) {
                    throw new MolangFrontendException(MolangFrontendException.Kind.SYNTAX, location, start,
                        "numeric exponent has no digits");
                }
            }
            return token(Kind.FLOAT, source.substring(start, offset), start);
        }

        private Token string(int start) throws MolangFrontendException {
            var value = new StringBuilder();
            while (offset < source.length()) {
                var current = source.charAt(offset++);
                if (current == '\'') {
                    var result = value.toString();
                    if (result.getBytes(StandardCharsets.UTF_8).length > 65_535) {
                        throw new LimitSignal("string literal exceeds 65535 UTF-8 bytes");
                    }
                    return token(Kind.STRING, result, start);
                }
                value.append(current);
            }
            throw new MolangFrontendException(MolangFrontendException.Kind.SYNTAX, location, start,
                "unclosed string literal");
        }

        private Token requiredPair(char expected, Kind kind, int start) throws MolangFrontendException {
            if (!match(expected)) {
                throw new MolangFrontendException(MolangFrontendException.Kind.SYNTAX, location, start,
                    "expected paired '" + expected + "'");
            }
            return token(kind, null, start);
        }

        private boolean match(char expected) {
            if (offset >= source.length() || source.charAt(offset) != expected) {
                return false;
            }
            offset++;
            return true;
        }

        private static Token token(Kind kind, String text, int offset) {
            return new Token(kind, text, offset);
        }

        private static boolean asciiLetter(char value) {
            return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
        }
    }

    private static final class LimitSignal extends RuntimeException {
        private LimitSignal(String message) {
            super(message);
        }
    }
}
