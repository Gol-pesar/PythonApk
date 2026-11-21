package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.python.PythonSyntaxTransformation
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSyntaxColors

private val QuickSymbols = listOf(
    "TAB" to "TAB",
    "(" to "()",
    ")" to ")",
    "[" to "[]",
    "]" to "]",
    "{" to "{}",
    "}" to "}",
    ":" to ":",
    "\"" to "\"\"",
    "'" to "''",
    "=" to " = ",
    "_" to "_",
    "#" to "# ",
    "->" to " -> ",
    "*" to "*"
)

@Composable
fun CodeEditorPanel(
    code: String,
    fontSizeSp: Int,
    tabSize: Int,
    wordWrap: Boolean,
    onCodeChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val syntaxColors = LocalSyntaxColors.current
    val syntaxTransform = remember(syntaxColors) { PythonSyntaxTransformation(syntaxColors) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(code)) }

    LaunchedEffect(code) {
        if (code != textFieldValue.text) {
            val safeCursor = textFieldValue.selection.end.coerceIn(0, code.length)
            textFieldValue = TextFieldValue(code, TextRange(safeCursor))
        }
    }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineCount = remember(textFieldValue.text) {
        textFieldValue.text.count { it == '\n' } + 1
    }
    val lineHeightSp = (fontSizeSp * 1.55f).sp
    val codeTextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp,
        color = syntaxColors.plainText
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(syntaxColors.editorBackground)
        ) {
            // Scrollable Editor + Line Gutter
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(verticalScroll)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    // Line Number Gutter (single cached Text node for smooth 60fps typing)
                    val gutterText = remember(lineCount) {
                        buildString(lineCount * 4) {
                            for (n in 1..lineCount) {
                                if (n > 1) append('\n')
                                append(n)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .fillMaxHeight()
                            .background(syntaxColors.gutterBackground)
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Text(
                            text = gutterText,
                            style = codeTextStyle.copy(
                                color = syntaxColors.gutterText,
                                fontSize = (fontSizeSp - 1).coerceAtLeast(10).sp,
                                textAlign = TextAlign.End
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Divider line between gutter and code
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    // Code Input Canvas
                    val fieldModifier = if (wordWrap) {
                        Modifier
                            .weight(1f)
                            .padding(12.dp)
                    } else {
                        Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                            .widthIn(min = 320.dp)
                            .padding(12.dp)
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { updated ->
                            textFieldValue = updated
                            onCodeChanged(updated.text)
                        },
                        textStyle = codeTextStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = syntaxTransform,
                        modifier = fieldModifier.testTag("python_code_editor"),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (textFieldValue.text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.editor_placeholder),
                                        style = codeTextStyle.copy(color = syntaxColors.comment)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Quick Python Coding Symbol Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(QuickSymbols) { (label, insertion) ->
                    val actualInsert = if (insertion == "TAB") " ".repeat(tabSize) else insertion
                    Surface(
                        onClick = {
                            val sel = textFieldValue.selection
                            val start = sel.min.coerceIn(0, textFieldValue.text.length)
                            val end = sel.max.coerceIn(0, textFieldValue.text.length)
                            val newText = textFieldValue.text.substring(0, start) +
                                actualInsert +
                                textFieldValue.text.substring(end)
                            // Place cursor inside pairs like (), [], {}, "", ''
                            val cursorOffset = when (actualInsert) {
                                "()", "[]", "{}", "\"\"", "''" -> start + 1
                                else -> start + actualInsert.length
                            }
                            textFieldValue = TextFieldValue(newText, TextRange(cursorOffset))
                            onCodeChanged(newText)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .testTag("symbol_key_${label.lowercase()}")
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (label == "TAB") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
