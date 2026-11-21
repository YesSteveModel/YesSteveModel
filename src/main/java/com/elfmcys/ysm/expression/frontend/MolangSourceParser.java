package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.compilation.TaskContext;
import dev.semaweave.program.ProgramNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

final class MolangSourceParser {
    private final MolangFrontend.SourceLocation location;
    private final Lexer lexer;
    private final TaskContext task;
    private final TreeSet<MolangFrontend.ReferencedSymbol> references =
        new TreeSet<>(MolangFrontend.ReferencedSymbol.ORDER);
    private final HashSet<String> locals = new HashSet<>();

    private Token current;
    private int tokenCount;
    private int nodeCount;
    private int depth;
    private int maximumDepth;

    MolangSourceParser(String source, MolangFrontend.SourceLocation location, TaskContext task)
        throws MolangFrontendException {
        this.location = Objects.requireNonNull(location, "location");
        this.task = task;
        this.lexer = new Lexer(source, location, task);
        advance();
    }

    Result parse(MolangFrontend.SourceMode mode) throws MolangFrontendException {
        var roots = new ArrayList<ProgramNode>();
        while (current.kind() != Kind.EOF) {
            if (accept(Kind.SEMICOLON)) {
                continue;
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

    private enum ParseKind {
        EXPRESSION, PREFIX, POSTFIX, ARGUMENTS, BLOCK
    }

    private static final class ParseFrame {
        private final ParseKind kind;
        private final int minimum;
        private int state;
        private ProgramNode left;
        private ProgramNode yes;
        private Operator operator;
        private ProgramNode.UnaryOperator unary;
        private List<String> parts;
        private final ArrayList<ProgramNode> nodes = new ArrayList<>();

        private ParseFrame(ParseKind kind, int minimum) {
            this.kind = kind;
            this.minimum = minimum;
        }
    }

    private ProgramNode parseExpression(int minimumPrecedence) throws MolangFrontendException {
        var pending = new ArrayDeque<ParseFrame>();
        pending.push(new ParseFrame(ParseKind.EXPRESSION, minimumPrecedence));
        ProgramNode value = null;
        List<ProgramNode> arguments = List.of();
        while (!pending.isEmpty()) {
            task.checkCancelled();
            var frame = pending.peek();
            switch (frame.kind) {
                case EXPRESSION -> {
                    if (frame.state == 0) {
                        depth++;
                        maximumDepth = Math.max(maximumDepth, depth);
                        frame.state = 1;
                        pending.push(new ParseFrame(ParseKind.PREFIX, 0));
                        continue;
                    }
                    if (frame.state == 1)
                        frame.left = value;
                    else if (frame.state == 2) {
                        frame.yes = value;
                        if (accept(Kind.COLON)) {
                            frame.state = 3;
                            pending.push(new ParseFrame(ParseKind.EXPRESSION, PREC_CONDITIONAL));
                            continue;
                        }
                        frame.left =
                            node(new ProgramNode.Conditional(frame.left, frame.yes, node(new ProgramNode.Unset())));
                    } else if (frame.state == 3) {
                        frame.left = node(new ProgramNode.Conditional(frame.left, frame.yes, value));
                    } else if (frame.state == 4) {
                        frame.left = switch (frame.operator.kind()) {
                            case COALESCE -> node(new ProgramNode.Coalesce(frame.left, value));
                            case ASSIGN -> assignment(frame.left, value);
                            case ARROW -> node(new ProgramNode.WithContext(frame.left, value));
                            default -> node(new ProgramNode.Binary(frame.operator.binary(), frame.left, value));
                        };
                    }
                    if (current.kind() == Kind.QUESTION && PREC_CONDITIONAL >= frame.minimum) {
                        advance();
                        frame.state = 2;
                        pending.push(new ParseFrame(ParseKind.EXPRESSION, PREC_CONDITIONAL));
                        continue;
                    }
                    var operator = binary(current.kind());
                    if (operator == null || operator.precedence() < frame.minimum) {
                        value = frame.left;
                        depth--;
                        pending.pop();
                        continue;
                    }
                    advance();
                    frame.operator = operator;
                    frame.state = 4;
                    pending.push(new ParseFrame(ParseKind.EXPRESSION, operator.precedence() + 1));
                }
                case PREFIX -> {
                    if (frame.state != 0) {
                        if (frame.state == 1)
                            value = lowerCall(frame.parts, arguments);
                        if (frame.state == 2) {
                            require(Kind.RPAREN, "expected ')'");
                            advance();
                        }
                        if (frame.state == 4)
                            value = node(new ProgramNode.Unary(frame.unary, value));
                        if (frame.state == 5)
                            value = node(new ProgramNode.Control(ProgramNode.ControlOperator.RETURN,
                                java.util.Optional.of(value)));
                        if (frame.state <= 3) {
                            frame.state = 6;
                            var postfix = new ParseFrame(ParseKind.POSTFIX, 0);
                            postfix.left = value;
                            pending.push(postfix);
                            continue;
                        }
                        pending.pop();
                        continue;
                    }
                    switch (current.kind()) {
                        case IDENTIFIER -> {
                            frame.parts = new ArrayList<>();
                            frame.parts.add(MolangNames.identifier(current.text()));
                            advance();
                            while (accept(Kind.DOT)) {
                                require(Kind.IDENTIFIER, "expected identifier after '.'");
                                frame.parts.add(MolangNames.identifier(current.text()));
                                advance();
                            }
                            if (accept(Kind.LPAREN)) {
                                frame.state = 1;
                                pending.push(new ParseFrame(ParseKind.ARGUMENTS, 0));
                            } else {
                                value = lowerRead(frame.parts);
                                frame.state = 6;
                                var postfix = new ParseFrame(ParseKind.POSTFIX, 0);
                                postfix.left = value;
                                pending.push(postfix);
                            }
                        }
                        case LPAREN -> {
                            advance();
                            frame.state = 2;
                            pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                        }
                        case LBRACE -> {
                            advance();
                            frame.state = 3;
                            pending.push(new ParseFrame(ParseKind.BLOCK, 0));
                        }
                        case PLUS, MINUS, BANG -> {
                            frame.unary = current.kind() == Kind.PLUS ? ProgramNode.UnaryOperator.POSITIVE
                                : current.kind() == Kind.MINUS ? ProgramNode.UnaryOperator.NEGATE
                                    : ProgramNode.UnaryOperator.LOGICAL_NOT;
                            advance();
                            frame.state = 4;
                            pending.push(new ParseFrame(ParseKind.EXPRESSION, PREC_PREFIX));
                        }
                        case RETURN -> {
                            advance();
                            if (current.kind() == Kind.SEMICOLON || current.kind() == Kind.RBRACE
                                || current.kind() == Kind.EOF) {
                                value = node(new ProgramNode.Control(ProgramNode.ControlOperator.RETURN,
                                    java.util.Optional.empty()));
                                pending.pop();
                            } else {
                                frame.state = 5;
                                pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                            }
                        }
                        case BREAK, CONTINUE -> {
                            value = node(
                                new ProgramNode.Control(current.kind() == Kind.BREAK ? ProgramNode.ControlOperator.BREAK
                                    : ProgramNode.ControlOperator.CONTINUE, java.util.Optional.empty()));
                            advance();
                            pending.pop();
                        }
                        case FLOAT -> {
                            var token = current;
                            advance();
                            try {
                                value = node(ProgramNode.F32.of(Float.parseFloat(token.text())));
                            } catch (NumberFormatException failure) {
                                throw failure(MolangFrontendException.Kind.SYNTAX, token.offset(),
                                    "invalid numeric literal", failure);
                            }
                            pending.pop();
                        }
                        case STRING -> {
                            value = node(new ProgramNode.StringLiteral(current.text()));
                            advance();
                            pending.pop();
                        }
                        case TRUE, FALSE -> {
                            value = node(new ProgramNode.Bool(current.kind() == Kind.TRUE));
                            advance();
                            pending.pop();
                        }
                        default -> throw failure(MolangFrontendException.Kind.SYNTAX, current.offset(),
                            "expected expression", null);
                    }
                }
                case POSTFIX -> {
                    if (frame.state == 1) {
                        require(Kind.RBRACKET, "expected ']'");
                        advance();
                        frame.left = node(new ProgramNode.Index(frame.left, value));
                        frame.state = 0;
                    }
                    if (accept(Kind.DOT)) {
                        require(Kind.IDENTIFIER, "expected identifier after '.'");
                        frame.left = node(new ProgramNode.Member(frame.left, MolangNames.identifier(current.text())));
                        advance();
                    } else if (accept(Kind.LBRACKET)) {
                        frame.state = 1;
                        pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                    } else {
                        if (current.kind() == Kind.LPAREN)
                            fail(MolangFrontendException.Kind.UNSUPPORTED, "only named symbols may be invoked");
                        value = frame.left;
                        pending.pop();
                    }
                }
                case ARGUMENTS -> {
                    if (frame.state == 1) {
                        frame.nodes.add(value);
                        if (accept(Kind.RPAREN)) {
                            arguments = List.copyOf(frame.nodes);
                            pending.pop();
                            continue;
                        }
                        require(Kind.COMMA, "expected ',' between arguments");
                        advance();
                        pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                        continue;
                    }
                    if (accept(Kind.RPAREN)) {
                        arguments = List.copyOf(frame.nodes);
                        pending.pop();
                    } else {
                        frame.state = 1;
                        pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                    }
                }
                case BLOCK -> {
                    if (frame.state == 1) {
                        frame.nodes.add(value);
                        if (current.kind() != Kind.RBRACE && !accept(Kind.SEMICOLON))
                            fail(MolangFrontendException.Kind.SYNTAX, "expected ';' or '}'");
                    }
                    while (accept(Kind.SEMICOLON)) {
                    }
                    if (current.kind() == Kind.EOF)
                        fail(MolangFrontendException.Kind.SYNTAX, "unclosed block");
                    if (accept(Kind.RBRACE)) {
                        value = node(new ProgramNode.Block(frame.nodes));
                        pending.pop();
                    } else {
                        frame.state = 1;
                        pending.push(new ParseFrame(ParseKind.EXPRESSION, 0));
                    }
                }
            }
        }
        return value;
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
        if (root.equals("math") && parts.size() == 2 && parts.get(1).equals("e")) {
            return node(ProgramNode.F32.of((float)java.lang.Math.E));
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
        if (parts.size() == 2 && (root.equals("query") || root.equals("q")) && parts.get(1).equals("debug_output")
            && !arguments.isEmpty()) {
            var steps = new ArrayList<ProgramNode>();
            steps.add(symbol(ProgramNode.SymbolDomain.EXTERNAL, "query:debug_output_begin",
                ProgramNode.AccessForm.INVOKE, List.of()));
            for (var argument : arguments)
                steps.add(symbol(ProgramNode.SymbolDomain.EXTERNAL, "query:debug_output_append",
                    ProgramNode.AccessForm.INVOKE, List.of(argument)));
            steps.add(symbol(ProgramNode.SymbolDomain.EXTERNAL, "query:debug_output_end", ProgramNode.AccessForm.INVOKE,
                List.of()));
            return node(
                new ProgramNode.Conditional(
                    symbol(ProgramNode.SymbolDomain.EXTERNAL, "query:debug_output_enabled",
                        ProgramNode.AccessForm.INVOKE, List.of()),
                    node(new ProgramNode.Block(steps)), node(new ProgramNode.Unset())));
        }
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

    private void addLocal(String name) {
        locals.add(name);
    }

    private <T extends ProgramNode> T node(T value) {
        nodeCount++;
        return value;
    }

    private void advance() throws MolangFrontendException {
        current = lexer.next();
        tokenCount++;
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
            case EQ -> new Operator(OpKind.ASSIGN, null, 1);
            case ARROW -> new Operator(OpKind.ARROW, null, 12);
            case QUESTION_QUESTION -> new Operator(OpKind.COALESCE, null, 2);
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
        return new Operator(OpKind.BINARY, operator, precedence);
    }

    private static final int PREC_CONDITIONAL = 4;
    private static final int PREC_PREFIX = 11;

    private enum OpKind {
        BINARY, COALESCE, ASSIGN, ARROW
    }

    private record Operator(OpKind kind, ProgramNode.BinaryOperator binary, int precedence) {
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

        private final TaskContext task;

        private Lexer(String source, MolangFrontend.SourceLocation location, TaskContext task) {
            this.task = task;
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
                    if ((offset & 255) == 0)
                        task.checkCancelled();
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
                if ((offset & 255) == 0)
                    task.checkCancelled();
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
                if ((offset & 255) == 0)
                    task.checkCancelled();
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
                    if ((offset & 255) == 0)
                        task.checkCancelled();
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
                if ((offset & 255) == 0)
                    task.checkCancelled();
                var current = source.charAt(offset++);
                if (current == '\'') {
                    var result = value.toString();
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

}
