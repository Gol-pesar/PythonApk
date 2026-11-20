package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.EditorSettings
import com.example.ui.ConsoleEntryKind
import com.example.ui.PyLaunchUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSyntaxColors

private val QuickReplSnippets = listOf(
    "import sys; sys.version",
    "frozendict(py='3.15.2', os='Android')",
    "[*row for row in [[1, 2], [3, 4]]]",
    "sentinel('UNSET')",
    "import math; math.factorial(15)",
    "dir()"
)

@Composable
fun ReplScreen(
    uiState: PyLaunchUiState,
    settings: EditorSettings,
    onExecuteCommand: (String) -> Unit,
    onSubmitInput: (String) -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val syntaxColors = LocalSyntaxColors.current
    val listState = rememberLazyListState()
    var commandText by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }

    LaunchedEffect(uiState.replEntries.size) {
        if (uiState.replEntries.isNotEmpty()) {
            listState.animateScrollToItem(uiState.replEntries.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(syntaxColors.terminalBackground)
    ) {
        // REPL Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.repl_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "UTF-8 Interactive Shell • >>>",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("repl_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.action_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.testTag("repl_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.action_reset_repl),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // REPL Output Stream (strictly LTR)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("repl_output_list")
                ) {
                    items(uiState.replEntries, key = { it.id }) { entry ->
                        val color = when (entry.kind) {
                            ConsoleEntryKind.PROMPT -> syntaxColors.prompt
                            ConsoleEntryKind.STDOUT -> syntaxColors.stdout
                            ConsoleEntryKind.RESULT -> syntaxColors.builtin
                            ConsoleEntryKind.STDERR -> syntaxColors.stderr
                            ConsoleEntryKind.SYSTEM -> syntaxColors.systemInfo
                            ConsoleEntryKind.INPUT_ECHO -> syntaxColors.string
                        }
                        Text(
                            text = entry.text.trimEnd('\n'),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * 1.45f).sp,
                            fontWeight = if (entry.kind == ConsoleEntryKind.PROMPT) FontWeight.SemiBold else FontWeight.Normal,
                            color = color,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Quick 3.15.2 Snippets Strip
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(QuickReplSnippets) { snippet ->
                    Surface(
                        onClick = { onExecuteCommand(snippet) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(20.dp)
                        )
                    ) {
                        Text(
                            text = snippet,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Command Input Bar
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isWaitingInput = uiState.replWaitingPrompt != null
                    Text(
                        text = if (isWaitingInput) "in>" else ">>>",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (isWaitingInput) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        placeholder = {
                            Text(
                                text = uiState.replWaitingPrompt
                                    ?: stringResource(R.string.repl_placeholder),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = settings.fontSizeSp.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (isWaitingInput) {
                                    onSubmitInput(commandText)
                                } else {
                                    onExecuteCommand(commandText)
                                }
                                commandText = ""
                                historyCursor = -1
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("repl_command_input")
                    )

                    if (uiState.replHistory.isNotEmpty() && !isWaitingInput) {
                        Column {
                            IconButton(
                                onClick = {
                                    val hist = uiState.replHistory
                                    val nextCursor = if (historyCursor < 0) hist.lastIndex else (historyCursor - 1).coerceAtLeast(0)
                                    historyCursor = nextCursor
                                    commandText = hist[nextCursor]
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.repl_history_prev),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val hist = uiState.replHistory
                                    if (historyCursor in hist.indices) {
                                        val nextCursor = historyCursor + 1
                                        if (nextCursor > hist.lastIndex) {
                                            historyCursor = -1
                                            commandText = ""
                                        } else {
                                            historyCursor = nextCursor
                                            commandText = hist[nextCursor]
                                        }
                                    }
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.repl_history_next),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            if (isWaitingInput) {
                                onSubmitInput(commandText)
                            } else {
                                onExecuteCommand(commandText)
                            }
                            commandText = ""
                            historyCursor = -1
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("repl_send_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.action_send),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
