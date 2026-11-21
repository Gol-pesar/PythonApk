package com.example.python

import java.math.BigInteger

class PyParser(
    private val tokens: List<PyToken>,
    private val fileName: String = "<main>"
) {
    private var pos = 0

    fun parseProgram(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        skipNewlines()
        while (!isAtEnd()) {
            statements.addAll(parseStatement())
            skipNewlines()
        }
        return statements
    }

    fun parseSingleExpression(): Expr {
        skipNewlines()
        val expr = parseTupleOrExpr()
        skipNewlines()
        return expr
    }

    private fun parseStatement(): List<Stmt> {
        val tok = current()
        if (tok.type == TokenType.AT) {
            return listOf(parseDecorated())
        }
        if (tok.type == TokenType.IDENTIFIER && tok.lexeme == "match" && isMatchHeader()) {
            return listOf(parseMatch())
        }

        return when (tok.type) {
            TokenType.IF -> listOf(parseIf())
            TokenType.WHILE -> listOf(parseWhile())
            TokenType.FOR -> listOf(parseFor(isAsync = false))
            TokenType.DEF -> listOf(parseFuncDef(emptyList(), isAsync = false))
            TokenType.ASYNC -> {
                advance()
                when (current().type) {
                    TokenType.DEF -> listOf(parseFuncDef(emptyList(), isAsync = true))
                    TokenType.FOR -> listOf(parseFor(isAsync = true))
                    TokenType.WITH -> listOf(parseWith(isAsync = true))
                    else -> throw syntaxError("expected 'def', 'for', or 'with' after 'async'")
                }
            }
            TokenType.CLASS -> listOf(parseClassDef(emptyList()))
            TokenType.TRY -> listOf(parseTry())
            TokenType.WITH -> listOf(parseWith(isAsync = false))
            else -> parseSimpleStmtLine()
        }
    }

    private fun isMatchHeader(): Boolean {
        // Heuristic: `match <expr>:` followed by NEWLINE INDENT
        var i = pos + 1
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE -> depth++
                TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE -> depth--
                TokenType.COLON -> if (depth == 0) return true
                TokenType.NEWLINE, TokenType.EOF, TokenType.ASSIGN -> if (depth == 0) return false
                else -> Unit
            }
            i++
        }
        return false
    }

    private fun parseSimpleStmtLine(): List<Stmt> {
        val result = mutableListOf<Stmt>()
        result.add(parseSimpleStmt())
        while (match(TokenType.SEMICOLON)) {
            if (check(TokenType.NEWLINE) || check(TokenType.EOF) || check(TokenType.DEDENT)) break
            result.add(parseSimpleStmt())
        }
        if (!check(TokenType.EOF) && !check(TokenType.DEDENT)) {
            expect(TokenType.NEWLINE, "expected newline after statement")
        }
        return result
    }

    private fun parseSimpleStmt(): Stmt {
        val line = current().line
        return when (current().type) {
            TokenType.PASS -> { advance(); Stmt.Pass(line) }
            TokenType.BREAK -> { advance(); Stmt.Break(line) }
            TokenType.CONTINUE -> { advance(); Stmt.Continue(line) }
            TokenType.RETURN -> {
                advance()
                val value = if (isStmtEnd()) null else parseTupleOrExpr()
                Stmt.Return(value, line)
            }
            TokenType.YIELD -> {
                advance()
                val value = if (isStmtEnd()) null else parseTupleOrExpr()
                Stmt.Yield(value, line)
            }
            TokenType.DEL -> {
                advance()
                val targets = mutableListOf(exprToTarget(parseExpr()))
                while (match(TokenType.COMMA)) {
                    if (isStmtEnd()) break
                    targets.add(exprToTarget(parseExpr()))
                }
                Stmt.Del(targets, line)
            }
            TokenType.ASSERT -> {
                advance()
                val test = parseExpr()
                val msg = if (match(TokenType.COMMA)) parseExpr() else null
                Stmt.Assert(test, msg, line)
            }
            TokenType.RAISE -> {
                advance()
                val exc = if (isStmtEnd()) null else parseExpr()
                if (check(TokenType.FROM)) {
                    advance()
                    parseExpr() // accept `raise X from Y`
                }
                Stmt.Raise(exc, line)
            }
            TokenType.GLOBAL -> {
                advance()
                val names = mutableListOf(expect(TokenType.IDENTIFIER, "expected identifier").lexeme)
                while (match(TokenType.COMMA)) {
                    names.add(expect(TokenType.IDENTIFIER, "expected identifier").lexeme)
                }
                Stmt.Global(names, line)
            }
            TokenType.NONLOCAL -> {
                advance()
                val names = mutableListOf(expect(TokenType.IDENTIFIER, "expected identifier").lexeme)
                while (match(TokenType.COMMA)) {
                    names.add(expect(TokenType.IDENTIFIER, "expected identifier").lexeme)
                }
                Stmt.Nonlocal(names, line)
            }
            TokenType.IMPORT -> parseImport(isLazy = false)
            TokenType.FROM -> parseFromImport(isLazy = false)
            else -> {
                if (current().type == TokenType.IDENTIFIER && current().lexeme == "lazy" &&
                    (peek(1).type == TokenType.IMPORT || peek(1).type == TokenType.FROM)
                ) {
                    advance()
                    if (check(TokenType.IMPORT)) parseImport(isLazy = true)
                    else parseFromImport(isLazy = true)
                } else {
                    parseExprOrAssignStmt()
                }
            }
        }
    }

    private fun parseExprOrAssignStmt(): Stmt {
        val line = current().line
        val first = parseTupleOrExpr(allowStarred = true)

        // Optional type annotation: `x: int = 5` or `x: int`
        if (match(TokenType.COLON)) {
            parseExpr() // discard type annotation at runtime
            return if (match(TokenType.ASSIGN)) {
                val rhs = parseTupleOrExpr()
                Stmt.Assign(listOf(exprToTarget(first)), rhs, line)
            } else {
                Stmt.Pass(line)
            }
        }

        // Augmented assignment
        val augOp = matchAugAssignOp()
        if (augOp != null) {
            val rhs = parseTupleOrExpr()
            return Stmt.AugAssign(exprToTarget(first), augOp, rhs, line)
        }

        // Chained assignment: a = b = expr
        if (match(TokenType.ASSIGN)) {
            val targets = mutableListOf(exprToTarget(first))
            var value = parseTupleOrExpr(allowStarred = true)
            while (match(TokenType.ASSIGN)) {
                targets.add(exprToTarget(value))
                value = parseTupleOrExpr(allowStarred = true)
            }
            return Stmt.Assign(targets, value, line)
        }

        return Stmt.ExprStmt(first, line)
    }

    private fun matchAugAssignOp(): String? {
        val op = when (current().type) {
            TokenType.PLUS_ASSIGN -> "+"
            TokenType.MINUS_ASSIGN -> "-"
            TokenType.STAR_ASSIGN -> "*"
            TokenType.SLASH_ASSIGN -> "/"
            TokenType.DOUBLE_SLASH_ASSIGN -> "//"
            TokenType.PERCENT_ASSIGN -> "%"
            TokenType.DOUBLE_STAR_ASSIGN -> "**"
            TokenType.AMP_ASSIGN -> "&"
            TokenType.PIPE_ASSIGN -> "|"
            TokenType.CARET_ASSIGN -> "^"
            TokenType.LSHIFT_ASSIGN -> "<<"
            TokenType.RSHIFT_ASSIGN -> ">>"
            else -> return null
        }
        advance()
        return op
    }

    private fun parseDecorated(): Stmt {
        val decorators = mutableListOf<Expr>()
        while (match(TokenType.AT)) {
            decorators.add(parseExpr())
            expect(TokenType.NEWLINE, "expected newline after decorator")
            skipNewlines()
        }
        return when (current().type) {
            TokenType.DEF -> parseFuncDef(decorators, isAsync = false)
            TokenType.ASYNC -> {
                advance()
                parseFuncDef(decorators, isAsync = true)
            }
            TokenType.CLASS -> parseClassDef(decorators)
            else -> throw syntaxError("expected 'def' or 'class' after decorator")
        }
    }

    private fun parseImport(isLazy: Boolean): Stmt.ImportStmt {
        val line = expect(TokenType.IMPORT, "expected 'import'").line
        val names = mutableListOf<Pair<String, String?>>()
        do {
            val modName = parseDottedName()
            val alias = if (match(TokenType.AS)) {
                expect(TokenType.IDENTIFIER, "expected alias name").lexeme
            } else null
            names.add(modName to alias)
        } while (match(TokenType.COMMA))
        return Stmt.ImportStmt(names, isLazy, line)
    }

    private fun parseFromImport(isLazy: Boolean): Stmt.FromImportStmt {
        val line = expect(TokenType.FROM, "expected 'from'").line
        while (match(TokenType.DOT)) { /* relative import prefix */ }
        val modName = parseDottedName()
        expect(TokenType.IMPORT, "expected 'import'")
        val names = mutableListOf<Pair<String, String?>>()
        if (match(TokenType.STAR)) {
            names.add("*" to null)
        } else {
            val hasParens = match(TokenType.LPAREN)
            do {
                if (hasParens && check(TokenType.RPAREN)) break
                val item = expect(TokenType.IDENTIFIER, "expected name to import").lexeme
                val alias = if (match(TokenType.AS)) {
                    expect(TokenType.IDENTIFIER, "expected alias").lexeme
                } else null
                names.add(item to alias)
            } while (match(TokenType.COMMA))
            if (hasParens) expect(TokenType.RPAREN, "expected ')'")
        }
        return Stmt.FromImportStmt(modName, names, isLazy, line)
    }

    private fun parseDottedName(): String {
        val parts = mutableListOf(expect(TokenType.IDENTIFIER, "expected module name").lexeme)
        while (match(TokenType.DOT)) {
            parts.add(expect(TokenType.IDENTIFIER, "expected identifier after '.'").lexeme)
        }
        return parts.joinToString(".")
    }

    private fun parseIf(): Stmt.If {
        val line = expect(TokenType.IF, "expected 'if'").line
        val branches = mutableListOf<Pair<Expr, List<Stmt>>>()
        val firstCond = parseExpr()
        expect(TokenType.COLON, "expected ':' after if condition")
        branches.add(firstCond to parseSuite())

        skipNewlines()
        while (match(TokenType.ELIF)) {
            val cond = parseExpr()
            expect(TokenType.COLON, "expected ':' after elif condition")
            branches.add(cond to parseSuite())
            skipNewlines()
        }
        val elseBody = if (match(TokenType.ELSE)) {
            expect(TokenType.COLON, "expected ':' after else")
            parseSuite()
        } else emptyList()

        return Stmt.If(branches, elseBody, line)
    }

    private fun parseWhile(): Stmt.While {
        val line = expect(TokenType.WHILE, "expected 'while'").line
        val cond = parseExpr()
        expect(TokenType.COLON, "expected ':' after while condition")
        val body = parseSuite()
        skipNewlines()
        val elseBody = if (match(TokenType.ELSE)) {
            expect(TokenType.COLON, "expected ':' after else")
            parseSuite()
        } else emptyList()
        return Stmt.While(cond, body, elseBody, line)
    }

    private fun parseFor(isAsync: Boolean = false): Stmt.For {
        val line = expect(TokenType.FOR, "expected 'for'").line
        val target = parseTargetList()
        expect(TokenType.IN, "expected 'in' in for loop")
        val iterable = parseTupleOrExpr()
        expect(TokenType.COLON, "expected ':' after for header")
        val body = parseSuite()
        skipNewlines()
        val elseBody = if (match(TokenType.ELSE)) {
            expect(TokenType.COLON, "expected ':' after else")
            parseSuite()
        } else emptyList()
        return Stmt.For(target, iterable, body, elseBody, line, isAsync = isAsync)
    }

    private fun parseFuncDef(decorators: List<Expr>, isAsync: Boolean = false): Stmt.FuncDef {
        val line = expect(TokenType.DEF, "expected 'def'").line
        val name = expect(TokenType.IDENTIFIER, "expected function name").lexeme
        expect(TokenType.LPAREN, "expected '(' after function name")
        val params = parseParamList(TokenType.RPAREN)
        expect(TokenType.RPAREN, "expected ')' after parameters")
        if (match(TokenType.ARROW)) {
            parseExpr() // return type annotation
        }
        expect(TokenType.COLON, "expected ':' after function signature")
        val body = parseSuite()
        return Stmt.FuncDef(name, params, body, decorators, line, isAsync = isAsync)
    }

    private fun parseClassDef(decorators: List<Expr>): Stmt.ClassDef {
        val line = expect(TokenType.CLASS, "expected 'class'").line
        val name = expect(TokenType.IDENTIFIER, "expected class name").lexeme
        val bases = mutableListOf<Expr>()
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    if (check(TokenType.RPAREN)) break
                    bases.add(parseExpr())
                } while (match(TokenType.COMMA))
            }
            expect(TokenType.RPAREN, "expected ')' after base classes")
        }
        expect(TokenType.COLON, "expected ':' after class header")
        val body = parseSuite()
        return Stmt.ClassDef(name, bases, body, decorators, line)
    }

    private fun parseTry(): Stmt.TryStmt {
        val line = expect(TokenType.TRY, "expected 'try'").line
        expect(TokenType.COLON, "expected ':' after try")
        val body = parseSuite()
        val handlers = mutableListOf<ExceptHandler>()
        skipNewlines()
        while (check(TokenType.EXCEPT)) {
            val hLine = advance().line
            var excType: Expr? = null
            var alias: String? = null
            if (!check(TokenType.COLON)) {
                excType = parseExpr()
                if (match(TokenType.AS)) {
                    alias = expect(TokenType.IDENTIFIER, "expected exception alias").lexeme
                }
            }
            expect(TokenType.COLON, "expected ':' after except")
            handlers.add(ExceptHandler(excType, alias, parseSuite(), hLine))
            skipNewlines()
        }
        val elseBody = if (match(TokenType.ELSE)) {
            expect(TokenType.COLON, "expected ':' after else")
            parseSuite()
        } else emptyList()
        skipNewlines()
        val finallyBody = if (match(TokenType.FINALLY)) {
            expect(TokenType.COLON, "expected ':' after finally")
            parseSuite()
        } else emptyList()
        return Stmt.TryStmt(body, handlers, elseBody, finallyBody, line)
    }

    private fun parseWith(isAsync: Boolean = false): Stmt.WithStmt {
        val line = expect(TokenType.WITH, "expected 'with'").line
        val items = mutableListOf<Pair<Expr, AssignTarget?>>()
        do {
            val ctxExpr = parseExpr()
            val target = if (match(TokenType.AS)) exprToTarget(parseExpr()) else null
            items.add(ctxExpr to target)
        } while (match(TokenType.COMMA))
        expect(TokenType.COLON, "expected ':' after with")
        val body = parseSuite()
        return Stmt.WithStmt(items, body, line, isAsync = isAsync)
    }

    private fun parseMatch(): Stmt.MatchStmt {
        val line = advance().line // 'match'
        val subject = parseTupleOrExpr()
        expect(TokenType.COLON, "expected ':' after match subject")
        expect(TokenType.NEWLINE, "expected newline after match header")
        skipNewlines()
        expect(TokenType.INDENT, "expected indented case block")
        val cases = mutableListOf<MatchCase>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            if (current().type != TokenType.IDENTIFIER || current().lexeme != "case") {
                throw syntaxError("expected 'case' in match statement")
            }
            val cLine = advance().line
            val pattern = parseMatchPattern()
            val guard = if (match(TokenType.IF)) parseExpr() else null
            expect(TokenType.COLON, "expected ':' after case pattern")
            val body = parseSuite()
            cases.add(MatchCase(pattern, guard, body, cLine))
            skipNewlines()
        }
        if (check(TokenType.DEDENT)) advance()
        return Stmt.MatchStmt(subject, cases, line)
    }

    private fun parseMatchPattern(): MatchPattern {
        val options = mutableListOf(parseSinglePattern())
        while (match(TokenType.PIPE)) {
            options.add(parseSinglePattern())
        }
        return if (options.size == 1) options[0] else MatchPattern.Or(options)
    }

    private fun parseSinglePattern(): MatchPattern {
        val tok = current()
        if (tok.type == TokenType.IDENTIFIER && tok.lexeme == "_") {
            advance()
            return MatchPattern.Wildcard
        }
        if (tok.type == TokenType.LBRACKET || tok.type == TokenType.LPAREN) {
            val endTok = if (tok.type == TokenType.LBRACKET) TokenType.RBRACKET else TokenType.RPAREN
            advance()
            val items = mutableListOf<MatchPattern>()
            if (!check(endTok)) {
                do {
                    if (check(endTok)) break
                    items.add(parseMatchPattern())
                } while (match(TokenType.COMMA))
            }
            expect(endTok, "expected closing bracket in pattern")
            return MatchPattern.Sequence(items)
        }
        if (tok.type == TokenType.IDENTIFIER && peek(1).type != TokenType.DOT) {
            advance()
            return MatchPattern.Capture(tok.lexeme)
        }
        return MatchPattern.Value(parsePrimary())
    }

    private fun parseSuite(): List<Stmt> {
        if (!check(TokenType.NEWLINE)) {
            return parseSimpleStmtLine()
        }
        expect(TokenType.NEWLINE, "expected newline")
        skipNewlines()
        expect(TokenType.INDENT, "expected indented block")
        val stmts = mutableListOf<Stmt>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            stmts.addAll(parseStatement())
            skipNewlines()
        }
        if (check(TokenType.DEDENT)) advance()
        return stmts
    }

    private fun parseParamList(endToken: TokenType): List<PyParam> {
        val params = mutableListOf<PyParam>()
        if (check(endToken)) return params
        do {
            if (check(endToken)) break
            if (match(TokenType.SLASH)) continue // positional-only marker
            var isStar = false
            var isDoubleStar = false
            when {
                match(TokenType.DOUBLE_STAR) -> isDoubleStar = true
                match(TokenType.STAR) -> {
                    if (check(TokenType.COMMA) || check(endToken)) continue // kw-only marker
                    isStar = true
                }
            }
            val name = expect(TokenType.IDENTIFIER, "expected parameter name").lexeme
            if (match(TokenType.COLON)) {
                parseExpr() // discard parameter type annotation
            }
            val defaultExpr = if (match(TokenType.ASSIGN)) parseExpr() else null
            params.add(PyParam(name, defaultExpr, isStar, isDoubleStar))
        } while (match(TokenType.COMMA))
        return params
    }

    private fun parseTargetList(): AssignTarget {
        val items = mutableListOf(parseSingleTarget())
        var isTuple = false
        while (match(TokenType.COMMA)) {
            isTuple = true
            if (check(TokenType.IN) || check(TokenType.COLON) || check(TokenType.ASSIGN)) break
            items.add(parseSingleTarget())
        }
        return if (isTuple) AssignTarget.Sequence(items) else items[0]
    }

    private fun parseSingleTarget(): AssignTarget {
        if (match(TokenType.STAR)) {
            return AssignTarget.Starred(parseSingleTarget())
        }
        if (match(TokenType.LPAREN)) {
            val inner = parseTargetList()
            expect(TokenType.RPAREN, "expected ')'")
            return inner
        }
        if (match(TokenType.LBRACKET)) {
            val inner = parseTargetList()
            expect(TokenType.RBRACKET, "expected ']'")
            return inner
        }
        val expr = parsePostfix()
        return exprToTarget(expr)
    }

    private fun exprToTarget(expr: Expr): AssignTarget = when (expr) {
        is Expr.Identifier -> AssignTarget.Name(expr.name)
        is Expr.Attribute -> AssignTarget.Attribute(expr.obj, expr.name)
        is Expr.Subscript -> AssignTarget.Subscript(expr.obj, expr.index)
        is Expr.TupleLiteral -> AssignTarget.Sequence(expr.elements.map { exprToTarget(it) })
        is Expr.ListLiteral -> AssignTarget.Sequence(expr.elements.map { exprToTarget(it) })
        is Expr.Starred -> AssignTarget.Starred(exprToTarget(expr.value))
        else -> throw syntaxError("cannot assign to expression at line ${expr.line}")
    }

    private fun parseTupleOrExpr(allowStarred: Boolean = false): Expr {
        val line = current().line
        val first = if (allowStarred && match(TokenType.STAR)) {
            Expr.Starred(parseExpr(), line)
        } else {
            parseExpr()
        }
        if (!check(TokenType.COMMA)) return first

        val items = mutableListOf(first)
        while (match(TokenType.COMMA)) {
            if (isStmtEnd() || check(TokenType.ASSIGN) || check(TokenType.COLON) ||
                check(TokenType.RPAREN) || check(TokenType.RBRACKET)
            ) break
            val next = if (allowStarred && match(TokenType.STAR)) {
                Expr.Starred(parseExpr(), current().line)
            } else {
                parseExpr()
            }
            items.add(next)
        }
        return Expr.TupleLiteral(items, line)
    }

    fun parseExpr(): Expr {
        val line = current().line
        if (check(TokenType.LAMBDA)) return parseLambda()
        val expr = parseConditional()
        if (expr is Expr.Identifier && match(TokenType.WALRUS)) {
            val value = parseExpr()
            return Expr.NamedExpr(expr.name, value, line)
        }
        return expr
    }

    private fun parseLambda(): Expr.Lambda {
        val line = expect(TokenType.LAMBDA, "expected 'lambda'").line
        val params = parseParamList(TokenType.COLON)
        expect(TokenType.COLON, "expected ':' in lambda")
        val body = parseExpr()
        return Expr.Lambda(params, body, line)
    }

    private fun parseConditional(): Expr {
        val thenExpr = parseOr()
        if (match(TokenType.IF)) {
            val cond = parseOr()
            expect(TokenType.ELSE, "expected 'else' in conditional expression")
            val elseExpr = parseExpr()
            return Expr.IfExpr(cond, thenExpr, elseExpr, thenExpr.line)
        }
        return thenExpr
    }

    private fun parseOr(): Expr {
        val first = parseAnd()
        if (!check(TokenType.OR)) return first
        val items = mutableListOf(first)
        while (match(TokenType.OR)) items.add(parseAnd())
        return Expr.BoolOp("or", items, first.line)
    }

    private fun parseAnd(): Expr {
        val first = parseNot()
        if (!check(TokenType.AND)) return first
        val items = mutableListOf(first)
        while (match(TokenType.AND)) items.add(parseNot())
        return Expr.BoolOp("and", items, first.line)
    }

    private fun parseNot(): Expr {
        if (match(TokenType.NOT)) {
            val line = previous().line
            return Expr.Unary("not", parseNot(), line)
        }
        return parseComparison()
    }

    private fun parseComparison(): Expr {
        val left = parseBitOr()
        val ops = mutableListOf<String>()
        val comparators = mutableListOf<Expr>()
        while (true) {
            val op = when {
                match(TokenType.EQ) -> "=="
                match(TokenType.NEQ) -> "!="
                match(TokenType.LT) -> "<"
                match(TokenType.GT) -> ">"
                match(TokenType.LE) -> "<="
                match(TokenType.GE) -> ">="
                match(TokenType.IN) -> "in"
                check(TokenType.NOT) && peek(1).type == TokenType.IN -> {
                    advance(); advance(); "not in"
                }
                match(TokenType.IS) -> {
                    if (match(TokenType.NOT)) "is not" else "is"
                }
                else -> null
            } ?: break
            ops.add(op)
            comparators.add(parseBitOr())
        }
        return if (ops.isEmpty()) left else Expr.Compare(left, ops, comparators, left.line)
    }

    private fun parseBitOr(): Expr {
        var left = parseBitXor()
        while (match(TokenType.PIPE)) {
            left = Expr.Binary(left, "|", parseBitXor(), left.line)
        }
        return left
    }

    private fun parseBitXor(): Expr {
        var left = parseBitAnd()
        while (match(TokenType.CARET)) {
            left = Expr.Binary(left, "^", parseBitAnd(), left.line)
        }
        return left
    }

    private fun parseBitAnd(): Expr {
        var left = parseShift()
        while (match(TokenType.AMPERSAND)) {
            left = Expr.Binary(left, "&", parseShift(), left.line)
        }
        return left
    }

    private fun parseShift(): Expr {
        var left = parseAdditive()
        while (check(TokenType.LSHIFT) || check(TokenType.RSHIFT)) {
            val op = advance().lexeme
            left = Expr.Binary(left, op, parseAdditive(), left.line)
        }
        return left
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = advance().lexeme
            left = Expr.Binary(left, op, parseMultiplicative(), left.line)
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parseUnary()
        while (check(TokenType.STAR) || check(TokenType.SLASH) ||
            check(TokenType.DOUBLE_SLASH) || check(TokenType.PERCENT)
        ) {
            val op = advance().lexeme
            left = Expr.Binary(left, op, parseUnary(), left.line)
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (check(TokenType.PLUS) || check(TokenType.MINUS) || check(TokenType.TILDE)) {
            val tok = advance()
            return Expr.Unary(tok.lexeme, parseUnary(), tok.line)
        }
        if (check(TokenType.AWAIT)) {
            val tok = advance()
            return Expr.Await(parseUnary(), tok.line)
        }
        return parsePower()
    }

    private fun parsePower(): Expr {
        val base = parsePostfix()
        if (match(TokenType.DOUBLE_STAR)) {
            val exp = parseUnary()
            return Expr.Binary(base, "**", exp, base.line)
        }
        return base
    }

    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            expr = when {
                match(TokenType.DOT) -> {
                    val name = expect(TokenType.IDENTIFIER, "expected attribute name after '.'").lexeme
                    Expr.Attribute(expr, name, expr.line)
                }
                match(TokenType.LPAREN) -> {
                    val args = parseCallArgs()
                    expect(TokenType.RPAREN, "expected ')' after arguments")
                    Expr.Call(expr, args, expr.line)
                }
                match(TokenType.LBRACKET) -> {
                    val index = parseSubscriptExpr()
                    expect(TokenType.RBRACKET, "expected ']'")
                    Expr.Subscript(expr, index, expr.line)
                }
                else -> return expr
            }
        }
    }

    private fun parseCallArgs(): List<CallArg> {
        val args = mutableListOf<CallArg>()
        if (check(TokenType.RPAREN)) return args
        do {
            if (check(TokenType.RPAREN)) break
            val arg = when {
                match(TokenType.DOUBLE_STAR) -> CallArg(value = parseExpr(), isDoubleStar = true)
                match(TokenType.STAR) -> CallArg(value = parseExpr(), isStar = true)
                check(TokenType.IDENTIFIER) && peek(1).type == TokenType.ASSIGN -> {
                    val name = advance().lexeme
                    advance() // '='
                    CallArg(name = name, value = parseExpr())
                }
                else -> {
                    val expr = parseExpr()
                    // Generator expression as sole call argument: `sum(x*x for x in range(5))`
                    if (check(TokenType.FOR)) {
                        val clauses = parseCompClauses()
                        CallArg(value = Expr.ListComp(expr, clauses, expr.line))
                    } else {
                        CallArg(value = expr)
                    }
                }
            }
            args.add(arg)
        } while (match(TokenType.COMMA))
        return args
    }

    private fun parseSubscriptExpr(): Expr {
        val line = current().line
        var start: Expr? = null
        if (!check(TokenType.COLON)) {
            start = parseExpr()
            if (!check(TokenType.COLON)) {
                // Possibly tuple subscript like `a[1, 2]`
                if (check(TokenType.COMMA)) {
                    val items = mutableListOf(start)
                    while (match(TokenType.COMMA)) {
                        if (check(TokenType.RBRACKET)) break
                        items.add(parseExpr())
                    }
                    return Expr.TupleLiteral(items, line)
                }
                return start
            }
        }
        expect(TokenType.COLON, "expected ':'")
        val stop = if (!check(TokenType.COLON) && !check(TokenType.RBRACKET)) parseExpr() else null
        val step = if (match(TokenType.COLON)) {
            if (!check(TokenType.RBRACKET)) parseExpr() else null
        } else null
        return Expr.SliceExpr(start, stop, step, line)
    }

    private fun parsePrimary(): Expr {
        val tok = current()
        return when (tok.type) {
            TokenType.INT -> {
                advance()
                val cleaned = tok.lexeme.replace("_", "")
                val big = when {
                    cleaned.startsWith("0x", true) -> BigInteger(cleaned.substring(2), 16)
                    cleaned.startsWith("0o", true) -> BigInteger(cleaned.substring(2), 8)
                    cleaned.startsWith("0b", true) -> BigInteger(cleaned.substring(2), 2)
                    else -> BigInteger(cleaned)
                }
                Expr.Literal(PyInt(big), tok.line)
            }
            TokenType.FLOAT -> {
                advance()
                val value = tok.lexeme.replace("_", "").toDoubleOrNull()
                    ?: throw syntaxError("invalid float literal '${tok.lexeme}'")
                Expr.Literal(PyFloat(value), tok.line)
            }
            TokenType.STRING -> {
                advance()
                // Support implicit string concatenation: "a" "b"
                val sb = StringBuilder(tok.lexeme)
                while (check(TokenType.STRING)) {
                    sb.append(advance().lexeme)
                }
                Expr.Literal(PyStr(sb.toString()), tok.line)
            }
            TokenType.FSTRING -> {
                advance()
                Expr.FString(tok.lexeme, tok.line)
            }
            TokenType.BYTES -> {
                advance()
                val sb = StringBuilder(tok.lexeme)
                while (check(TokenType.BYTES)) {
                    sb.append(advance().lexeme)
                }
                val bytes = ByteArray(sb.length) { idx -> (sb[idx].code and 0xFF).toByte() }
                Expr.Literal(PyBytes(bytes), tok.line)
            }
            TokenType.TRUE -> { advance(); Expr.Literal(PyBool.TRUE, tok.line) }
            TokenType.FALSE -> { advance(); Expr.Literal(PyBool.FALSE, tok.line) }
            TokenType.NONE -> { advance(); Expr.Literal(PyNone, tok.line) }
            TokenType.ELLIPSIS -> { advance(); Expr.Literal(PySentinel("Ellipsis", "Ellipsis"), tok.line) }
            TokenType.IDENTIFIER -> { advance(); Expr.Identifier(tok.lexeme, tok.line) }
            TokenType.LPAREN -> parseParenExpr()
            TokenType.LBRACKET -> parseListOrComp()
            TokenType.LBRACE -> parseDictOrSet()
            else -> throw syntaxError("unexpected token '${tok.lexeme}' (${tok.type})")
        }
    }

    private fun parseParenExpr(): Expr {
        val line = expect(TokenType.LPAREN, "expected '('").line
        if (match(TokenType.RPAREN)) return Expr.TupleLiteral(emptyList(), line)

        val first = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), line) else parseExpr()

        // Generator expression `(x for x in seq)` -> evaluated lazily/eagerly as ListComp
        if (check(TokenType.FOR)) {
            val clauses = parseCompClauses()
            expect(TokenType.RPAREN, "expected ')' after generator expression")
            return Expr.ListComp(first, clauses, line)
        }

        if (match(TokenType.COMMA)) {
            val items = mutableListOf(first)
            if (!check(TokenType.RPAREN)) {
                do {
                    if (check(TokenType.RPAREN)) break
                    val elem = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), current().line) else parseExpr()
                    items.add(elem)
                } while (match(TokenType.COMMA))
            }
            expect(TokenType.RPAREN, "expected ')'")
            return Expr.TupleLiteral(items, line)
        }

        expect(TokenType.RPAREN, "expected ')'")
        return first
    }

    private fun parseListOrComp(): Expr {
        val line = expect(TokenType.LBRACKET, "expected '['").line
        if (match(TokenType.RBRACKET)) return Expr.ListLiteral(emptyList(), line)

        // Supports PEP 798 unpacking in comprehensions: `[*row for row in matrix]`
        val first = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), current().line) else parseExpr()

        if (check(TokenType.FOR)) {
            val clauses = parseCompClauses()
            expect(TokenType.RBRACKET, "expected ']' after list comprehension")
            return Expr.ListComp(first, clauses, line)
        }

        val items = mutableListOf(first)
        while (match(TokenType.COMMA)) {
            if (check(TokenType.RBRACKET)) break
            val elem = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), current().line) else parseExpr()
            items.add(elem)
        }
        expect(TokenType.RBRACKET, "expected ']'")
        return Expr.ListLiteral(items, line)
    }

    private fun parseDictOrSet(): Expr {
        val line = expect(TokenType.LBRACE, "expected '{'").line
        if (match(TokenType.RBRACE)) return Expr.DictLiteral(emptyList(), line)

        // Dict unpacking literal or PEP 798 dict comprehension `{**d for d in dicts}`
        if (match(TokenType.DOUBLE_STAR)) {
            val firstVal = parseExpr()
            if (check(TokenType.FOR)) {
                val clauses = parseCompClauses()
                expect(TokenType.RBRACE, "expected '}' after dict comprehension")
                return Expr.DictComp(null, firstVal, isUnpack = true, clauses = clauses, line = line)
            }
            val entries = mutableListOf(DictEntry(null, firstVal))
            while (match(TokenType.COMMA)) {
                if (check(TokenType.RBRACE)) break
                entries.add(parseDictEntry())
            }
            expect(TokenType.RBRACE, "expected '}'")
            return Expr.DictLiteral(entries, line)
        }

        // Set unpacking literal or PEP 798 set comprehension `{*s for s in sets}`
        if (match(TokenType.STAR)) {
            val first = Expr.Starred(parseExpr(), current().line)
            if (check(TokenType.FOR)) {
                val clauses = parseCompClauses()
                expect(TokenType.RBRACE, "expected '}' after set comprehension")
                return Expr.SetComp(first, clauses, line)
            }
            val items = mutableListOf<Expr>(first)
            while (match(TokenType.COMMA)) {
                if (check(TokenType.RBRACE)) break
                val elem = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), current().line) else parseExpr()
                items.add(elem)
            }
            expect(TokenType.RBRACE, "expected '}'")
            return Expr.SetLiteral(items, line)
        }

        val first = parseExpr()
        if (match(TokenType.COLON)) {
            val firstVal = parseExpr()
            if (check(TokenType.FOR)) {
                val clauses = parseCompClauses()
                expect(TokenType.RBRACE, "expected '}' after dict comprehension")
                return Expr.DictComp(first, firstVal, isUnpack = false, clauses = clauses, line = line)
            }
            val entries = mutableListOf(DictEntry(first, firstVal))
            while (match(TokenType.COMMA)) {
                if (check(TokenType.RBRACE)) break
                entries.add(parseDictEntry())
            }
            expect(TokenType.RBRACE, "expected '}'")
            return Expr.DictLiteral(entries, line)
        }

        // Set comprehension or set literal
        if (check(TokenType.FOR)) {
            val clauses = parseCompClauses()
            expect(TokenType.RBRACE, "expected '}' after set comprehension")
            return Expr.SetComp(first, clauses, line)
        }

        val items = mutableListOf(first)
        while (match(TokenType.COMMA)) {
            if (check(TokenType.RBRACE)) break
            val elem = if (match(TokenType.STAR)) Expr.Starred(parseExpr(), current().line) else parseExpr()
            items.add(elem)
        }
        expect(TokenType.RBRACE, "expected '}'")
        return Expr.SetLiteral(items, line)
    }

    private fun parseDictEntry(): DictEntry {
        if (match(TokenType.DOUBLE_STAR)) {
            return DictEntry(null, parseExpr())
        }
        val k = parseExpr()
        expect(TokenType.COLON, "expected ':' in dict literal")
        val v = parseExpr()
        return DictEntry(k, v)
    }

    private fun parseCompClauses(): List<CompClause> {
        val clauses = mutableListOf<CompClause>()
        while (match(TokenType.FOR)) {
            val target = parseTargetList()
            expect(TokenType.IN, "expected 'in' in comprehension")
            val iterable = parseOr()
            val conditions = mutableListOf<Expr>()
            while (match(TokenType.IF)) {
                conditions.add(parseOr())
            }
            clauses.add(CompClause(target, iterable, conditions))
        }
        return clauses
    }

    private fun skipNewlines() {
        while (check(TokenType.NEWLINE)) advance()
    }

    private fun isStmtEnd(): Boolean =
        check(TokenType.NEWLINE) || check(TokenType.SEMICOLON) || check(TokenType.EOF) || check(TokenType.DEDENT)

    private fun current(): PyToken = tokens[pos.coerceAtMost(tokens.lastIndex)]
    private fun previous(): PyToken = tokens[(pos - 1).coerceAtLeast(0)]
    private fun peek(offset: Int): PyToken = tokens[(pos + offset).coerceAtMost(tokens.lastIndex)]
    private fun isAtEnd(): Boolean = current().type == TokenType.EOF
    private fun check(type: TokenType): Boolean = current().type == type

    private fun advance(): PyToken {
        if (!isAtEnd()) pos++
        return previous()
    }

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun expect(type: TokenType, message: String): PyToken {
        if (check(type)) return advance()
        throw syntaxError("$message, got '${current().lexeme}'")
    }

    private fun syntaxError(message: String): PySyntaxError =
        PySyntaxError(message, current().line, fileName)
}
