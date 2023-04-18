package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.List;

class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
    final Environment globals = new Environment();
    private Environment environment = globals;
    private static Object uninitialized = new Object();

    private static class BreakException extends RuntimeException {
    }

    Interpreter() {
        globals.define("clock", new LoxCallable() {
            @Override
            public int arity() {
                return 0;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments) {
                return (double) System.currentTimeMillis() / 1000.0;
            }

            @Override
            public String toString() {
                return "<native fn>";
            }
        });
    }

    // Interpret series of statements given in a list of Tokens
    /*
program        → declaration* EOF ;
        declaration    → varDecl | statement ;
        statement      → exprStmt | printStmt ;
        exprStmt       → expression ";" ;
        printStmt      → "print" expression ";" ;

varDecl        → "var" IDENTIFIER ( "=" expression )? ";" ;

statements → exprStmt |
             ifStmt |
             printStmt |
             block |
             whileStmt |
             forStmt;

ifStmt → "if" "(" expression ")" statement ( "else" statement)? ;
whileStmt → "while" "(" expression ")" statement ;
forStmt -> "for" "("  (varDecl | exprStmt | ";") expression? ";" expression? ")" statement;

    */
    void interpret(List<Stmt> statements) {
        try {
            for (Stmt statement : statements) {
                execute(statement);
            }
        } catch (RuntimeError error) {
            Lox.runtimeError(error);
        }
    }

    // Enables for recursive evaluating -> occurs until eval Literals
    // expr.accept(this) calls Expr to recursively call interpreter to interpret expression
    private void execute(Stmt stmt) {
        stmt.accept(this);
    }

    // Creating new environment to execute statements in a block scope
    void executeBlock(List<Stmt> statements, Environment environment) {
        Environment previous = this.environment;
        try {
            this.environment = environment;

            for (Stmt statement : statements) {
                execute(statement);
            }
        } finally {
            this.environment = previous;
        }
    }

    // Enables for recursive evaluating -> occurs until eval Literals
    // expr.accept(this) calls Expr to recursively call interpreter to interpret expression
    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }

    // Need to not return anything as no values produced;
    // Expression interpreter
    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        evaluate(stmt.expression);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        LoxFunction function = new LoxFunction(stmt);
        environment.define(stmt.name.lexeme, function);
        return null;
    }

    // If statement interpretation
    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        if (isTruthy(evaluate(stmt.condition))) {
            execute(stmt.thenBranch);
        } else if (stmt.elseBranch != null) {
            execute(stmt.elseBranch);
        }
        return null;
    }

    // While statement interpreter
    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        try {
            while (isTruthy(evaluate(stmt.condition))) {
                execute(stmt.body);
            }
        } catch (BreakException ex) {

        }
        return null;
    }

    // Break Statement
    @Override
    public Void visitBreakStmt(Stmt.Break stmt) {
        throw new BreakException();
    }

    // Create print interpreter
    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        Object value = evaluate(stmt.expression);
        System.out.println(stringify(value));
        return null;
    }

    //  Declaration statements
    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        Object value = uninitialized;
        if (stmt.initializer != null) {
            value = evaluate(stmt.initializer);
        }

        environment.define(stmt.name.lexeme, value);
        return null;
    }

    // Interpreting Block statements
    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        executeBlock(stmt.statements, new Environment(environment));
        return null;
    }

    // Evaluating comma collections
    @Override
    public Object visitCommaCollectionExpr(Expr.CommaCollection expr) {
        evaluate(expr.left); // Evaluate and discard

        return evaluate(expr.right);
    }

    @Override
    public Object visitTernaryExpr(Expr.Ternary expr) {
        if (isTruthy(evaluate(expr.condition))) {
            return evaluate(expr.trueLeg);
        } else {
            return evaluate(expr.falseLeg);
        }
    }

    // Get the variable name
    @Override
    public Object visitVariableExpr(Expr.Variable expr) {
        Object value = environment.get(expr.name);
        if (value == uninitialized) {
            throw new RuntimeError(expr.name, "Variable must be initialized before use.");
        }
        return value;
    }

    // visit assigned expression
    @Override
    public Object visitAssignExpr(Expr.Assign expr) {
        Object value = evaluate(expr.value);
        environment.assign(expr.name, value);
        return value;
    }

    // Evaluating Literals
    @Override
    public Object visitLiteralExpr(Expr.Literal expr) {
        return expr.value;
    }

    @Override
    public Object visitLogicalExpr(Expr.Logical expr) {
        Object left = evaluate(expr.left);

        if (expr.operator.type == TokenType.OR) {
            if (isTruthy(left)) return left;
        } else {
            if (!isTruthy(left)) return left;
        }

        return evaluate(expr.right);
    }

    // Evaluating parentheses
    @Override
    public Object visitGroupingExpr(Expr.Grouping expr) {
        return evaluate(expr.expression);
    }

    //Evaluating unary expressions
    @Override
    public Object visitUnaryExpr(Expr.Unary expr) {
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {
            case BANG:
                return !isTruthy(right);
            case MINUS:
                return -(double) right;
        }
        // Unreachable due to error detection in Parsing
        return null;
    }

    // call       → primary ( "(" arguments? ")" )* ;
    @Override
    public Object visitCallExpr(Expr.Call expr) {
        Object callee = evaluate(expr.callee);

        List<Object> arguments = new ArrayList<>();
        for (Expr argument : expr.arguments) {
            arguments.add(evaluate(argument));
        }

        if (!(callee instanceof LoxCallable)) {
            throw new RuntimeError(expr.paren, "Can only call functions and classes.");
        }

        LoxCallable function = (LoxCallable) callee;
        if (arguments.size() != function.arity()) {
            throw new RuntimeError(expr.paren, "Expected " + function.arity()
                    + " arguments but got " + arguments.size() + ".");
        }

        return function.call(this, arguments);
    }

    @Override
    public Object visitBinaryExpr(Expr.Binary expr) {
        Object left = evaluate(expr.left);
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {
            case GREATER:
                checkNumberOperands(expr.operator, left, right);
                return (double) left > (double) right;
            case GREATER_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double) left >= (double) right;
            case LESS:
                checkNumberOperands(expr.operator, left, right);
                return (double) left < (double) right;
            case LESS_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double) left <= (double) right;
            case MINUS:
                checkNumberOperands(expr.operator, left, right);
                checkNumberOperand(expr.operator, right);
                return (double) left - (double) right;
            case PLUS:
                if (left instanceof Double && right instanceof Double) {
                    return (double) left + (double) right;
                }
                if (left instanceof String && right instanceof String) {
                    return (String) left + (String) right;
                }
                if (left instanceof Double && right instanceof String) {
                    return (stringify(left) + (String) right);
                }
                if (left instanceof String && right instanceof Double) {
                    return ((String) left + stringify(right));
                }
                throw new RuntimeError(expr.operator,
                        "Operands must be numbers or strings.");
            case SLASH:
                checkNumberOperands(expr.operator, left, right);
                if ((double) right != 0.0) {
                    return (double) left / (double) right;
                }
                if ((double) left == 0.0) {
                    return Double.NaN;
                }
                throw new RuntimeError(expr.operator, "Cannot divide by 0");
            case STAR:
                checkNumberOperands(expr.operator, left, right);
                return (double) left * (double) right;
            case BANG_EQUAL:
                return !isEqual(left, right);
            case EQUAL_EQUAL:
                return isEqual(left, right);
        }

        // Unreachable
        return null;
    }

    // Check operand
    private void checkNumberOperand(Token operator, Object operand) {
        if (operand instanceof Double) return;
        throw new RuntimeError(operator, "Operand must be a number.");
    }

    // Check operands
    private void checkNumberOperands(Token operator,
                                     Object left, Object right) {
        if (left instanceof Double && right instanceof Double) return;
        throw new RuntimeError(operator, "Operands must be numbers.");
    }

    // Evaluates if object is true or false
    // Follows Ruby ==> ie. false & nil == FALSE, else TRUE
    private boolean isTruthy(Object object) {
        if (object == null) return false;
        if (object instanceof Boolean) return (boolean) object;
        return true;
    }

    // checks if left and right is equal
    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;

        return a.equals(b);
    }

    // Takes syntax tree of expression and converts to string
    private String stringify(Object object) {
        if (object == null) return "nil";

        if (object instanceof Double) {
            String text = object.toString();
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            return text;
        }
        return object.toString();
    }

}
