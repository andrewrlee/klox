package klox.interpreter

import klox.Lox
import klox.ast.Expr
import klox.ast.Expr.*
import klox.ast.Stmt
import klox.interpreter.TokenType.*

class ParserError : RuntimeException()

class Parser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!isAtEnd()) {
            declaration()?.let { stmts.add(it) }
        }
        return stmts
    }

    private fun declaration(): Stmt? {
        return try {
            when {
                match(CLASS) -> classDeclaration()
                match(FUN) -> function("function")
                match(VAR) -> varDeclaration()
                else -> statement()
            }
        } catch (e: ParserError) {
            synchronize()
            null
        }
    }

    private fun classDeclaration(): Stmt {
        val name = consume(IDENTIFIER, "Expect class name.")
        consume(LEFT_BRACE, "Expect '{' before class body.")
        val methods = mutableListOf<Stmt.Function>()
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            methods.add(function("method"))
        }
        consume(RIGHT_BRACE, "Expect '}' after class body.")

        return Stmt.Class(name, methods)
    }

    private fun function(kind: String): Stmt.Function {
        val name = consume(IDENTIFIER, "Expect $kind name.")
        consume(LEFT_PAREN, "Expect '(' after $kind name.")
        val params = mutableListOf<Token>()
        if (!check(RIGHT_PAREN)) {
            do {
                if (params.size >= 255) {
                    error(peek(), "Can't have 255 or more parameters.")
                }
                params.add(consume(IDENTIFIER, "Expected parameter name."))
            } while (match(COMMA))
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.")
        consume(LEFT_BRACE, "Expect '{' before $kind body.")
        val body = block()

        return Stmt.Function(name, params, body)
    }

    private fun varDeclaration(): Stmt {
        val name = consume(IDENTIFIER, "Expect variable name.")
        val initializer = if (match(EQUAL)) expression() else null
        consume(SEMICOLON, "Expected a ';' after variable declaration")
        return Stmt.Var(name, initializer)
    }

    private fun statement() = when {
        match(FOR) -> forStatement()
        match(IF) -> ifStatement()
        match(PRINT) -> printStatement()
        match(WHILE) -> whileStatement()
        match(RETURN) -> returnStatement()
        match(LEFT_BRACE) -> Stmt.Block(block())
        else -> expressionStatement()
    }

    private fun forStatement(): Stmt {

        consume(LEFT_PAREN, "Expect '(' after 'for'")

        val initializer = when {
            match(SEMICOLON) -> null
            match(VAR) -> varDeclaration()
            else -> expressionStatement()
        }

        val condition = if (!check(SEMICOLON)) expression() else Literal(true)

        consume(SEMICOLON, "Expect ';' after loop condition")

        val increment = if (!check(RIGHT_PAREN)) Stmt.Expression(expression()) else null

        consume(RIGHT_PAREN, "Expect ')' after 'for' clauses")

        var body = statement()

        if (increment != null) {
            body = Stmt.Block(listOf(body, increment))
        }

        body = Stmt.While(condition, body)

        if (initializer != null) {
            body = Stmt.Block(listOf(initializer, body))
        }

        return body
    }

    private fun whileStatement(): Stmt {
        consume(LEFT_PAREN, "Expected '(' after 'while'")
        val condition = expression()
        consume(RIGHT_PAREN, "Expected ')' after 'while condition'")
        val body = statement()
        return Stmt.While(condition, body)
    }

    private fun ifStatement(): Stmt {
        consume(LEFT_PAREN, "expect '(' after 'if'")
        val condition = expression()
        consume(RIGHT_PAREN, "expect ')' after 'if' condition")
        val thenStmt = statement()
        val elseStmt = if (match(ELSE)) statement() else null
        return Stmt.If(condition, thenStmt, elseStmt)
    }

    private fun block(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            declaration()?.let { statements.add(it) }
        }
        consume(RIGHT_BRACE, "Expect '}' after block.")
        return statements
    }

    private fun printStatement(): Stmt {
        val value = expression()
        consume(SEMICOLON, "Expect ';' after value;")
        return Stmt.Print(value)
    }

    private fun expressionStatement(): Stmt {
        val value = expression()
        consume(SEMICOLON, "Expect ';' after value;")
        return Stmt.Expression(value)
    }

    private fun returnStatement(): Stmt {
        val keyword = previous()
        var value: Expr? = null
        if (!check(SEMICOLON)) {
            value = expression()
        }
        consume(SEMICOLON, "Expected ';' after return value.")
        return Stmt.Return(keyword, value)
    }

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = or()

        if (match(EQUAL)) {
            val equals = previous()
            val value = assignment()
            when (expr) {
                is Variable -> return Assign(expr.name, value)
                is Get -> return Set(expr.obj, expr.name, value)
                else -> error(equals, "Invalid assignment target.")
            }
        }

        return expr
    }

    private fun or() = parseBinaryOperator(OR) { and() }

    private fun and() = parseBinaryOperator(AND) { equality() }

    private fun equality() = parseBinaryOperator(BANG_EQUAL, EQUAL_EQUAL) { comparison() }

    private fun comparison() = parseBinaryOperator(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL) { term() }

    private fun term() = parseBinaryOperator(MINUS, PLUS) { factor() }

    private fun factor() = parseBinaryOperator(SLASH, STAR) { unary() }

    private fun unary(): Expr {
        if (match(BANG, MINUS)) {
            val operator = previous()
            val right = unary()
            return Unary(operator, right)
        }
        return call()
    }


    private fun call(): Expr {
        var expr = primary()
        while (true) {
            if (match(LEFT_PAREN)) {
                expr = finishCall(expr)
            } else if (match(DOT)) {
                val name = consume(IDENTIFIER, "Expect property name after '.'.")
                expr = Get(expr, name)

            } else {
                break;
            }

        }
        return expr
    }

    private fun finishCall(callee: Expr): Expr {
        val args = mutableListOf<Expr>()
        if (!check(RIGHT_PAREN)) {
            do {
                args.add(expression())
            } while (match(COMMA))
        }
        if (args.size >= 255) {
            error(peek(), "Can't have 255 or more arguments")
        }
        val paren = consume(RIGHT_PAREN, "Expect ')' after arguments")
        return Call(callee, paren, args)
    }

    private fun primary(): Expr {
        if (match(FALSE)) return Literal(false)
        if (match(TRUE)) return Literal(true)
        if (match(NIL)) return Literal(null)
        if (match(NUMBER, STRING)) return Literal(previous().literal)
        if (match(THIS)) return This(previous())
        if (match(IDENTIFIER)) return Variable(previous())

        if (match(LEFT_PAREN)) {
            val expr = expression()
            consume(RIGHT_PAREN, "Expect ')' after expression.")
            return Grouping(expr)
        }

        throw error(peek(), "Expect expression.")
    }

    private fun parseBinaryOperator(vararg operators: TokenType, operand: () -> Expr): Expr {
        var expr = operand()
        while (match(*operators)) {
            val operator = previous()
            val right = operand()
            expr = Binary(expr, operator, right)
        }
        return expr
    }

    private fun match(vararg tokenTypes: TokenType): Boolean {
        for (type in tokenTypes) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    val TYPES_TO_SYNC_ON = setOf(CLASS, FOR, FUN, IF, PRINT, RETURN, VAR, WHILE)
    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type == SEMICOLON) return
            if (TYPES_TO_SYNC_ON.contains(peek().type)) return
            advance()
        }
    }

    private fun consume(type: TokenType, message: String) =
        if (check(type)) advance() else throw error(peek(), message)

    private fun error(token: Token, message: String): ParserError {
        Lox.error(token, message)
        return ParserError()
    }

    private fun check(type: TokenType) = if (isAtEnd()) false else peek().type == type

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd() = peek().type == EOF
    private fun peek() = tokens[current]
    private fun previous() = tokens[current - 1]
}