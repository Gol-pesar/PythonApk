package com.example.python

class PyLexer(
    private val source: String,
    private val fileName: String = "<main>"
) {
    private val tokens = mutableListOf<PyToken>()
    private val indentStack = ArrayDeque<Int>().apply { addLast(0) }
    private var pos = 0
    private var line = 1
    private var col = 1
    private var bracketDepth = 0
    private var atLineStart = true

    private val keywords = mapOf(
        "False" to TokenType.FALSE,
        "None" to TokenType.NONE,
        "True" to TokenType.TRUE,
        "and" to TokenType.AND,
        "as" to TokenType.AS,
        "assert" to TokenType.ASSERT,
        "async" to TokenType.ASYNC,
        "await" to TokenType.AWAIT,
        "break" to TokenType.BREAK,
        "class" to TokenType.CLASS,
        "continue" to TokenType.CONTINUE,
        "def" to TokenType.DEF,
        "del" to TokenType.DEL,
        "elif" to TokenType.ELIF,
        "else" to TokenType.ELSE,
        "except" to TokenType.EXCEPT,
        "finally" to TokenType.FINALLY,
        "for" to TokenType.FOR,
        "from" to TokenType.FROM,
        "global" to TokenType.GLOBAL,
        "if" to TokenType.IF,
        "import" to TokenType.IMPORT,
        "in" to TokenType.IN,
        "is" to TokenType.IS,
        "lambda" to TokenType.LAMBDA,
        "nonlocal" to TokenType.NONLOCAL,
        "not" to TokenType.NOT,
        "or" to TokenType.OR,
        "pass" to TokenType.PASS,
        "raise" to TokenType.RAISE,
        "return" to TokenType.RETURN,
        "try" to TokenType.TRY,
        "while" to TokenType.WHILE,
        "with" to TokenType.WITH,
        "yield" to TokenType.YIELD
    )

    fun tokenize(): List<PyToken> {
        while (pos < source.length) {
            while (atLineStart && bracketDepth == 0 && pos < source.length) {
                handleIndentation()
            }
            if (pos >= source.length) break

            val ch = source[pos]
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> advance()
                ch == '\\' && peek(1) == '\n' -> {
                    advance(); advance()
                }
                ch == '\\' && peek(1) == '\r' && peek(2) == '\n' -> {
                    advance(); advance(); advance()
                }
                ch == '#' -> skipComment()
                ch == '\n' -> {
                    val l = line
                    val c = col
                    advance()
                    if (bracketDepth == 0) {
                        if (tokens.isNotEmpty() && tokens.last().type != TokenType.NEWLINE && tokens.last().type != TokenType.INDENT) {
                            tokens.add(PyToken(TokenType.NEWLINE, "\\n", l, c))
                        }
                        atLineStart = true
                    }
                }
                isStringPrefix() -> readString()
                ch == '\'' || ch == '"' -> readString()
                ch.isDigit() || (ch == '.' && peek(1)?.isDigit() == true) -> readNumber()
                isIdentStart(ch) -> readIdentifier()
                else -> readOperatorOrDelimiter()
            }
        }

        if (tokens.isNotEmpty() && tokens.last().type != TokenType.NEWLINE &&
            tokens.last().type != TokenType.INDENT && tokens.last().type != TokenType.DEDENT
        ) {
            tokens.add(PyToken(TokenType.NEWLINE, "\\n", line, col))
        }
        while (indentStack.size > 1) {
            indentStack.removeLast()
            tokens.add(PyToken(TokenType.DEDENT, "", line, col))
        }
        tokens.add(PyToken(TokenType.EOF, "", line, col))
        return tokens
    }

    private fun handleIndentation() {
        var indent = 0
        var scanPos = pos
        while (scanPos < source.length) {
            when (source[scanPos]) {
                ' ' -> { indent++; scanPos++ }
                '\t' -> { indent += 4 - (indent % 4); scanPos++ }
                '\r' -> scanPos++
                else -> break
            }
        }
        // Skip blank lines and comment-only lines
        if (scanPos >= source.length) {
            pos = scanPos
            atLineStart = false
            return
        }
        val nextCh = source[scanPos]
        if (nextCh == '\n') {
            pos = scanPos
            advance()
            atLineStart = true
            return
        }
        if (nextCh == '#') {
            pos = scanPos
            skipComment()
            if (pos < source.length && source[pos] == '\n') {
                advance()
            }
            atLineStart = true
            return
        }

        // Advance pos past leading whitespace
        while (pos < scanPos) advance()
        atLineStart = false

        val currentIndent = indentStack.last()
        when {
            indent > currentIndent -> {
                indentStack.addLast(indent)
                tokens.add(PyToken(TokenType.INDENT, "", line, col))
            }
            indent < currentIndent -> {
                while (indentStack.size > 1 && indentStack.last() > indent) {
                    indentStack.removeLast()
                    tokens.add(PyToken(TokenType.DEDENT, "", line, col))
                }
                if (indentStack.last() != indent) {
                    throw PySyntaxError("unindent does not match any outer indentation level", line, fileName)
                }
            }
        }
    }

    private fun skipComment() {
        while (pos < source.length && source[pos] != '\n') {
            advance()
        }
    }

    private fun isStringPrefix(): Boolean {
        var i = 0
        while (i < 2 && pos + i < source.length) {
            val c = source[pos + i].lowercaseChar()
            if (c in setOf('f', 'r', 'b', 'u')) {
                i++
            } else {
                break
            }
        }
        if (i == 0 || pos + i >= source.length) return false
        val q = source[pos + i]
        return q == '\'' || q == '"'
    }

    private fun readString() {
        val startLine = line
        val startCol = col
        var prefix = ""
        while (pos < source.length && source[pos] != '\'' && source[pos] != '"') {
            prefix += source[pos].lowercaseChar()
            advance()
        }
        val isRaw = prefix.contains('r')
        val isFString = prefix.contains('f')

        val quote = source[pos]
        val triple = peek(1) == quote && peek(2) == quote
        if (triple) {
            advance(); advance(); advance()
        } else {
            advance()
        }

        val sb = StringBuilder()
        while (pos < source.length) {
            val c = source[pos]
            if (triple) {
                if (c == quote && peek(1) == quote && peek(2) == quote) {
                    advance(); advance(); advance()
                    val type = if (isFString) TokenType.FSTRING else TokenType.STRING
                    tokens.add(PyToken(type, sb.toString(), startLine, startCol))
                    return
                }
            } else {
                if (c == quote) {
                    advance()
                    val type = if (isFString) TokenType.FSTRING else TokenType.STRING
                    tokens.add(PyToken(type, sb.toString(), startLine, startCol))
                    return
                }
                if (c == '\n') {
                    throw PySyntaxError("unterminated string literal", startLine, fileName)
                }
            }

            if (c == '\\' && !isRaw) {
                advance()
                if (pos >= source.length) throw PySyntaxError("unterminated string literal", startLine, fileName)
                val esc = source[pos]
                advance()
                when (esc) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '0' -> sb.append('\u0000')
                    'b' -> sb.append('\b')
                    '\n' -> Unit
                    'u' -> {
                        if (pos + 4 <= source.length) {
                            val hex = source.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                repeat(4) { advance() }
                                sb.append(code.toChar())
                            } else {
                                sb.append("\\u")
                            }
                        } else {
                            sb.append("\\u")
                        }
                    }
                    'x' -> {
                        if (pos + 2 <= source.length) {
                            val hex = source.substring(pos, pos + 2)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                repeat(2) { advance() }
                                sb.append(code.toChar())
                            } else {
                                sb.append("\\x")
                            }
                        } else {
                            sb.append("\\x")
                        }
                    }
                    else -> {
                        sb.append('\\')
                        sb.append(esc)
                    }
                }
            } else {
                sb.append(c)
                advance()
            }
        }
        throw PySyntaxError("unterminated string literal", startLine, fileName)
    }

    private fun readNumber() {
        val startLine = line
        val startCol = col
        val sb = StringBuilder()
        var isFloat = false

        if (source[pos] == '0' && peek(1)?.lowercaseChar() in setOf('x', 'o', 'b')) {
            sb.append(source[pos]); advance()
            sb.append(source[pos]); advance()
            while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
                sb.append(source[pos]); advance()
            }
            tokens.add(PyToken(TokenType.INT, sb.toString(), startLine, startCol))
            return
        }

        while (pos < source.length && (source[pos].isDigit() || source[pos] == '_')) {
            sb.append(source[pos]); advance()
        }
        if (pos < source.length && source[pos] == '.' && peek(1) != '.') {
            isFloat = true
            sb.append(source[pos]); advance()
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '_')) {
                sb.append(source[pos]); advance()
            }
        }
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            isFloat = true
            sb.append(source[pos]); advance()
            if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) {
                sb.append(source[pos]); advance()
            }
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '_')) {
                sb.append(source[pos]); advance()
            }
        }

        tokens.add(
            PyToken(
                type = if (isFloat) TokenType.FLOAT else TokenType.INT,
                lexeme = sb.toString(),
                line = startLine,
                column = startCol
            )
        )
    }

    private fun readIdentifier() {
        val startLine = line
        val startCol = col
        val sb = StringBuilder()
        while (pos < source.length && isIdentPart(source[pos])) {
            sb.append(source[pos])
            advance()
        }
        val text = sb.toString()
        val type = keywords[text] ?: TokenType.IDENTIFIER
        tokens.add(PyToken(type, text, startLine, startCol))
    }

    private fun readOperatorOrDelimiter() {
        val startLine = line
        val startCol = col
        val ch = source[pos]
        val next = peek(1)
        val third = peek(2)

        fun emit(type: TokenType, len: Int) {
            val lex = source.substring(pos, pos + len)
            repeat(len) { advance() }
            tokens.add(PyToken(type, lex, startLine, startCol))
        }

        // 3-char operators
        when {
            ch == '.' && next == '.' && third == '.' -> { emit(TokenType.ELLIPSIS, 3); return }
            ch == '*' && next == '*' && third == '=' -> { emit(TokenType.DOUBLE_STAR_ASSIGN, 3); return }
            ch == '/' && next == '/' && third == '=' -> { emit(TokenType.DOUBLE_SLASH_ASSIGN, 3); return }
            ch == '<' && next == '<' && third == '=' -> { emit(TokenType.LSHIFT_ASSIGN, 3); return }
            ch == '>' && next == '>' && third == '=' -> { emit(TokenType.RSHIFT_ASSIGN, 3); return }
        }

        // 2-char operators
        when {
            ch == '*' && next == '*' -> { emit(TokenType.DOUBLE_STAR, 2); return }
            ch == '/' && next == '/' -> { emit(TokenType.DOUBLE_SLASH, 2); return }
            ch == '<' && next == '<' -> { emit(TokenType.LSHIFT, 2); return }
            ch == '>' && next == '>' -> { emit(TokenType.RSHIFT, 2); return }
            ch == '=' && next == '=' -> { emit(TokenType.EQ, 2); return }
            ch == '!' && next == '=' -> { emit(TokenType.NEQ, 2); return }
            ch == '<' && next == '=' -> { emit(TokenType.LE, 2); return }
            ch == '>' && next == '=' -> { emit(TokenType.GE, 2); return }
            ch == ':' && next == '=' -> { emit(TokenType.WALRUS, 2); return }
            ch == '-' && next == '>' -> { emit(TokenType.ARROW, 2); return }
            ch == '+' && next == '=' -> { emit(TokenType.PLUS_ASSIGN, 2); return }
            ch == '-' && next == '=' -> { emit(TokenType.MINUS_ASSIGN, 2); return }
            ch == '*' && next == '=' -> { emit(TokenType.STAR_ASSIGN, 2); return }
            ch == '/' && next == '=' -> { emit(TokenType.SLASH_ASSIGN, 2); return }
            ch == '%' && next == '=' -> { emit(TokenType.PERCENT_ASSIGN, 2); return }
            ch == '&' && next == '=' -> { emit(TokenType.AMP_ASSIGN, 2); return }
            ch == '|' && next == '=' -> { emit(TokenType.PIPE_ASSIGN, 2); return }
            ch == '^' && next == '=' -> { emit(TokenType.CARET_ASSIGN, 2); return }
        }

        // 1-char operators & delimiters
        when (ch) {
            '+' -> emit(TokenType.PLUS, 1)
            '-' -> emit(TokenType.MINUS, 1)
            '*' -> emit(TokenType.STAR, 1)
            '/' -> emit(TokenType.SLASH, 1)
            '%' -> emit(TokenType.PERCENT, 1)
            '@' -> emit(TokenType.AT, 1)
            '&' -> emit(TokenType.AMPERSAND, 1)
            '|' -> emit(TokenType.PIPE, 1)
            '^' -> emit(TokenType.CARET, 1)
            '~' -> emit(TokenType.TILDE, 1)
            '<' -> emit(TokenType.LT, 1)
            '>' -> emit(TokenType.GT, 1)
            '=' -> emit(TokenType.ASSIGN, 1)
            ',' -> emit(TokenType.COMMA, 1)
            ':' -> emit(TokenType.COLON, 1)
            '.' -> emit(TokenType.DOT, 1)
            ';' -> emit(TokenType.SEMICOLON, 1)
            '(' -> { bracketDepth++; emit(TokenType.LPAREN, 1) }
            ')' -> { if (bracketDepth > 0) bracketDepth--; emit(TokenType.RPAREN, 1) }
            '[' -> { bracketDepth++; emit(TokenType.LBRACKET, 1) }
            ']' -> { if (bracketDepth > 0) bracketDepth--; emit(TokenType.RBRACKET, 1) }
            '{' -> { bracketDepth++; emit(TokenType.LBRACE, 1) }
            '}' -> { if (bracketDepth > 0) bracketDepth--; emit(TokenType.RBRACE, 1) }
            else -> throw PySyntaxError("invalid character '$ch'", startLine, fileName)
        }
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || c.isLetter()
    private fun isIdentPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    private fun peek(offset: Int): Char? {
        val idx = pos + offset
        return if (idx in source.indices) source[idx] else null
    }

    private fun advance() {
        if (pos < source.length) {
            if (source[pos] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            pos++
        }
    }
}
