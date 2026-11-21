package com.example.python

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ui.theme.CodeSyntaxColors

class PythonSyntaxTransformation(
    private val colors: CodeSyntaxColors
) : VisualTransformation {

    private val keywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "match", "case", "lazy"
    )

    private val builtins = setOf(
        "print", "input", "len", "range", "int", "float", "str", "bool",
        "list", "tuple", "dict", "set", "frozendict", "sentinel", "open",
        "sum", "min", "max", "sorted", "reversed", "enumerate", "zip",
        "map", "filter", "any", "all", "abs", "round", "pow", "type",
        "isinstance", "dir", "repr", "format", "chr", "ord", "hex", "bin",
        "oct", "iter", "next", "super", "property", "staticmethod", "classmethod",
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError", "ZeroDivisionError"
    )

    private var lastSource: String? = null
    private var lastTransformed: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw == lastSource) {
            lastTransformed?.let { return it }
        }
        val transformed = TransformedText(highlight(raw), OffsetMapping.Identity)
        lastSource = raw
        lastTransformed = transformed
        return transformed
    }

    fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        append(source)
        val len = source.length
        var i = 0
        var prevWord = ""

        while (i < len) {
            val c = source[i]
            when {
                // Comment
                c == '#' -> {
                    val start = i
                    while (i < len && source[i] != '\n') i++
                    addStyle(
                        SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic),
                        start,
                        i
                    )
                }
                // Decorator
                c == '@' && (i == 0 || source[i - 1] == '\n' || source[i - 1] == ' ') -> {
                    val start = i
                    i++
                    while (i < len && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.')) i++
                    addStyle(
                        SpanStyle(color = colors.decorator, fontWeight = FontWeight.Medium),
                        start,
                        i
                    )
                }
                // String literal (including f/r/b prefix)
                c == '\'' || c == '"' || ((c == 'f' || c == 'r' || c == 'b' || c == 'F' || c == 'R') &&
                    i + 1 < len && (source[i + 1] == '\'' || source[i + 1] == '"')) -> {
                    val start = i
                    if (c != '\'' && c != '"') i++
                    val q = source[i]
                    val triple = i + 2 < len && source[i + 1] == q && source[i + 2] == q
                    i += if (triple) 3 else 1
                    while (i < len) {
                        if (source[i] == '\\' && i + 1 < len) {
                            i += 2
                        } else if (triple) {
                            if (i + 2 < len && source[i] == q && source[i + 1] == q && source[i + 2] == q) {
                                i += 3; break
                            }
                            i++
                        } else {
                            if (source[i] == q || source[i] == '\n') {
                                if (source[i] == q) i++
                                break
                            }
                            i++
                        }
                    }
                    addStyle(SpanStyle(color = colors.string), start, i)
                }
                // Number
                c.isDigit() -> {
                    val start = i
                    while (i < len && (source[i].isLetterOrDigit() || source[i] == '.' || source[i] == '_')) i++
                    addStyle(SpanStyle(color = colors.number), start, i)
                }
                // Identifier / Keyword / Builtin
                c == '_' || c.isLetter() -> {
                    val start = i
                    while (i < len && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val word = source.substring(start, i)
                    when {
                        word in keywords -> addStyle(
                            SpanStyle(color = colors.keyword, fontWeight = FontWeight.SemiBold),
                            start,
                            i
                        )
                        prevWord == "def" || prevWord == "class" -> addStyle(
                            SpanStyle(color = colors.functionName, fontWeight = FontWeight.Bold),
                            start,
                            i
                        )
                        word in builtins -> addStyle(
                            SpanStyle(color = colors.builtin),
                            start,
                            i
                        )
                    }
                    prevWord = word
                }
                else -> {
                    if (!c.isWhitespace()) prevWord = ""
                    i++
                }
            }
        }
    }
}
