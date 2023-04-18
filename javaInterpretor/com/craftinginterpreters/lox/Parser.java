package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
expression     → literal        Literal(Object value)
               | unary          Unary(Token operator, Expr right)
               | binary         Binary(Expr left, Token operator, Expr right)
               | grouping       Grouping(Expr expression)

varDecl        → "var" IDENTIFIER ( "=" expression )? ";" ;
funDecl        → "fun" function ;
function      -> INDENTIFIER "(" parameters? ")" block ;

AST - Syntax tree

expression → comma ;
comma      → assignment ( "," assignment )* ;
assignment → IDENTIFIER "=" assignment | conditional ;
conditional→ or ( "?" or ":" or )? ;
               | logic_or ;
logic_or       → logic_and ( "or" logic_and )* ;
logic_and      → equality ( "and" equality )* ;
equality   → comparison ( ( "!=" | "==" ) comparison )* ;
comparison → term ( ( ">" | ">=" | "<" | "<=" ) term )* ;
term       → factor ( ( "-" | "+" ) factor )* ;
factor     → unary ( ( "/" | "*" ) unary )* ;
unary      → ( "!" | "-" | "--" | "++" ) unary
           | postfix ;
postfix    → call ( "--" | ++" )* ;
call       → primary ( "(" arguments? ")" )* ;
primary    → NUMBER | STRING | "true" | "false" | "nil"
           | "(" expression ")" | IDENTIFIER ;

arguments      → expression ( "," expression )* ;

   // Error productions...
           | ( "!=" | "==" ) equality
           | ( ">" | ">=" | "<" | "<=" ) comparison
           | ( "+" ) term
           | ( "/" | "*" ) factor ;

program statements     → declaration* EOF ;
        declaration    → varDecl | statement | funDecl ;
statements → exprStmt |
             ifStmt |
             printStmt |
             block |
             whileStmt |
             forStmt;
            block      → "{" declaration "}" ;
        exprStmt       → expression ";" ;
        printStmt      → "print" expression ";"

Stmt
                "Expression : Expr expression",
                "Print      : Expr expression",
                "Var        : Token name, Expr initializer"
                "Block      : List<Stmt> statements",

*/

class Parser {
    private static class ParseError extends RuntimeException {
    }

    private final List<Token> tokens;
    private int current = 0;
    private int loopDepth = 0;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    //program        → statement* EOF ;
    List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }
        return statements;
    }

    // declaration    → varDecl | statement ;
    private Stmt declaration() {
        try {
            if (match(TokenType.VAR)) return varDeclaration();
            if (match(TokenType.FUN)) return function("function");
            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt.Function function(String kind) {
        Token name = consume(TokenType.IDENTIFIER, "Expect " + kind + " name.");
        List<Token> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (parameters.size() >= 255) {
                    error(peek(), "Can't have more than 255 parameters.");
                }
                parameters.add(consume(TokenType.IDENTIFIER, "Expect parameter name."));
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_PAREN, "Expect ')' after parameters.");

        consume(TokenType.LEFT_BRACE, "Expect '{' before " + kind + " body.");
        List<Stmt> body = block();
        return new Stmt.Function(name, parameters, body);
    }

    // varDecl → "var" IDENTIFIER ( "=" expression )? ";" ;
    private Stmt varDeclaration() {
        Token name = consume(TokenType.IDENTIFIER, "Expect variable name");

        Expr initializer = null;
        if (match(TokenType.EQUAL)) {
            initializer = expression();
        }

        consume(TokenType.SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, initializer);
    }

    //statement      → exprStmt | printStmt | block;
    private Stmt statement() {
        if (match(TokenType.IF)) return ifStatement();
        if (match(TokenType.PRINT)) return printStatement();
        if (match(TokenType.FOR)) return forStatement();
        if (match(TokenType.WHILE)) return whileStatement();
        if (match(TokenType.LEFT_BRACE)) return new Stmt.Block(block());
        if (match(TokenType.BREAK)) return breakStatement();
        return expressionStatement();
    }

    // if statement   --> ifStmt         → "if" "(" expression ")" statement
    //               ( "else" statement )? ;
    private Stmt ifStatement() {
        consume(TokenType.LEFT_PAREN, "Expect '(' after 'if'.");
        Expr condition = expression();
        consume(TokenType.RIGHT_PAREN, "Expect ')' after if condition");

        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(TokenType.ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(condition, thenBranch, elseBranch);
    }

    // while statement -->
    private Stmt whileStatement() {
        consume(TokenType.LEFT_PAREN, "Expect '(' after 'if'.");
        Expr condition = expression();
        consume(TokenType.RIGHT_PAREN, "Expect ')' after if condition");

        try {
            loopDepth++;
            Stmt body = statement();

            return new Stmt.While(condition, body);
        } finally {
            loopDepth--;
        }

    }

    // for statement
    // forStmt -> "for" "("  (varDecl | exprStmt | ";") expression? ";" expression? ")" statement;
    // for parameters ==> initializer, condition, increment
    private Stmt forStatement() {
        consume(TokenType.LEFT_PAREN, "Expect '(' after 'for'.");
        Stmt initializer;
        if (match(TokenType.SEMICOLON)) {
            initializer = null;
        } else if (match(TokenType.VAR)) {
            initializer = varDeclaration();
        } else {
            initializer = expressionStatement();
        }

        Expr condition = null;
        if (!check(TokenType.SEMICOLON)) {
            condition = expression();
        }
        consume(TokenType.SEMICOLON, "Expect ';' after loop condition");

        Expr increment = null;
        if (!check(TokenType.SEMICOLON)) {
            increment = expression();
        }
        consume(TokenType.RIGHT_PAREN, "Expect ')' after for loop parameters");

        try {
            loopDepth++;
            Stmt body = statement();

            if (increment != null) {
                body = new Stmt.Block(
                        Arrays.asList(body, new Stmt.Expression(increment))
                );
            }

            if (condition == null) condition = new Expr.Literal(true);
            body = new Stmt.While(condition, body);

            if (initializer != null) {
                body = new Stmt.Block(Arrays.asList(initializer, body));
            }
            return body;
        } finally {
            loopDepth--;
        }
    }

    // printStmt      → "print" expression ";" ;
    private Stmt printStatement() {
        Expr value = expression();
        consume(TokenType.SEMICOLON, "Expect ';' after value.");
        return new Stmt.Print(value);
    }

    // Break statement
    private Stmt breakStatement() {
        if (loopDepth == 0) {
            error(previous(), "Must be inside a loop to use 'break'.");
        }

        consume(TokenType.SEMICOLON, "Expect ';' after 'break'.");
        return new Stmt.Break();
    }

    //  exprStmt       → expression ";" ;
    private Stmt expressionStatement() {
        Expr value = expression();
        consume(TokenType.SEMICOLON, "Expect ';' after value.");
        return new Stmt.Expression(value);
    }

    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();

        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }
        consume(TokenType.RIGHT_BRACE, "Expect '}' after block.");
        return statements;
    }

    // expression → comma ;
    private Expr expression() {
        return comma();
    }

    //Comma → assignment ( "," assignment )* ;
    private Expr comma() {
        Expr expr = assignment();
        while (match(TokenType.COMMA)) {
            Token operator = previous();
            Expr right = assignment();
            expr = new Expr.CommaCollection(expr, operator, right);
        }
        return expr;
    }

    // assignment → IDENTIFIER "=" assignment | conditional ;
    private Expr assignment() {
        Expr expr = conditional();

        if (match(TokenType.EQUAL)) {
            Token equals = previous();
            Expr value = assignment();

            if (expr instanceof Expr.Variable) {
                Token name = ((Expr.Variable) expr).name;
                return new Expr.Assign(name, value);
            }

            error(equals, "Invalid assignment target.");
        }

        return expr;
    }

    // conditional -- Utilizes right-associativity
    // conditional → or ( "?" expression ":" conditional )? ;
    // conditional→ or ( "?" or ":" or )? ;
    //           | logic_or ;

    private Expr conditional() {
        Expr expr = or();

        if (match(TokenType.QUESTION)) {
            Expr ifBranch = or();
            consume(TokenType.COLON, "Expect ':' after conditional expression ?");
            Expr elseBranch = conditional();
            expr = new Expr.Ternary(expr, ifBranch, elseBranch);
        }

        return expr;
    }

    private Expr or() {
        Expr expr = and();

        while (match(TokenType.OR)) {
            Token operator = previous();
            Expr right = and();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        Expr expr = equality();

        while (match(TokenType.AND)) {
            Token operator = previous();
            Expr right = equality();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    // equality   → comparison ( ( "!=" | "==" ) comparison )* ;
    private Expr equality() {
        Expr expr = comparison();

        while (match(TokenType.BANG_EQUAL, TokenType.EQUAL_EQUAL)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr comparison() {
        Expr expr = term();

        while (match(TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL)) {
            Token operator = previous();
            Expr right = term();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr term() {
        Expr expr = factor();

        while (match(TokenType.MINUS, TokenType.PLUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr factor() {
        Expr expr = unary();

        while (match(TokenType.SLASH, TokenType.STAR)) {
            Token operator = previous();
            Expr right = unary();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr unary() {
        if (match(TokenType.BANG, TokenType.MINUS)) {
            Token operator = previous();
            Expr right = unary();
            return new Expr.Unary(operator, right);
        }

        return primary();
    }

    // call       → primary ( "(" arguments? ")" )* ;
    // arguments is multiple expressions
    private Expr call() {
        Expr expr = primary();
        while (true) {
            if (match(TokenType.LEFT_PAREN)) {
                expr = finishCall(expr);
            } else {
                break;
            }
        }
        return expr;
    }

    // finishCall
    private Expr finishCall(Expr callee) {
        List<Expr> arguments = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 255) {
                    error(peek(), "Can't have more than 255 arguments.");
                }
                arguments.add(expression());
            } while (match(TokenType.COMMA));
        }

        Token paren = consume(TokenType.RIGHT_PAREN, "Expect ')' after arguments.");
        return new Expr.Call(callee, paren, arguments);
    }


    private Expr primary() {
        if (match(TokenType.FALSE)) return new Expr.Literal(false);
        if (match(TokenType.TRUE)) return new Expr.Literal(true);
        if (match(TokenType.NIL)) return new Expr.Literal(null);

        if (match(TokenType.NUMBER, TokenType.STRING)) {
            return new Expr.Literal(previous().literal);
        }

        if (match(TokenType.IDENTIFIER)) {
            return new Expr.Variable(previous());
        }

        if (match(TokenType.LEFT_PAREN)) {
            Expr expr = expression();
            consume(TokenType.RIGHT_PAREN, "Expect ')' after expression.");
            return new Expr.Grouping(expr);
        }

        //     Error productions
        //   | ( "!=" | "==" ) equality
        //        | ( ">" | ">=" | "<" | "<=" ) comparison
        //        | ( "+" ) term
        //        | ( "/" | "*" ) factor ;
        if (match(TokenType.BANG_EQUAL, TokenType.EQUAL_EQUAL)) {
            error(previous(), "Missing left-hand operand.");
            equality();
            return null;
        }

        if (match(TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL)) {
            error(previous(), "Missing left-hand operand.");
            comparison();
            return null;
        }

        if (match(TokenType.PLUS)) {
            error(previous(), "Missing left-hand operand.");
            term();
            return null;
        }

        if (match(TokenType.SLASH, TokenType.STAR)) {
            error(previous(), "Missing left-hand operand.");
            factor();
            return null;
        }

        throw error(peek(), "Expect expression.");
    }

    // Checks if current token is of types wanted
    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }

        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();

        throw error(peek(), message);
    }

    private ParseError error(Token token, String message) {
        Lox.error(token, message);
        return new ParseError();
    }

    // Advance until at the next probable statement so parsing can continue
    private void synchronize() {
        advance();

        while (!isAtEnd()) {
            if (previous().type == TokenType.SEMICOLON) return;

            switch (peek().type) {
                case CLASS:
                case FUN:
                case VAR:
                case FOR:
                case IF:
                case WHILE:
                case PRINT:
                case RETURN:
                    return;
            }

            advance();
        }
    }


    // Checks if this current token is of type wanted
    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }

    // Consumes current token and returns it
    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    // Check if at end of code
    private boolean isAtEnd() {
        return peek().type == TokenType.EOF;
    }

    // check current token w/o consuming it
    private Token peek() {
        return tokens.get(current);
    }

    // checks the previous token
    private Token previous() {
        return tokens.get(current - 1);
    }

}
