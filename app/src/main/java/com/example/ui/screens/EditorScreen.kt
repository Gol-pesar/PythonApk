package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.EditorSettings
import com.example.ui.PyLaunchUiState
import com.example.ui.RunStatus
import com.example.ui.components.CodeEditorPanel
import com.example.ui.components.OutputConsolePanel
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun EditorScreen(
    uiState: PyLaunchUiState,
    settings: EditorSettings,
    onCodeChanged: (String) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRequestNewFile: () -> Unit,
    onToggleOutput: () -> Unit,
    onClearOutput: () -> Unit,
    onSubmitInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Editor Top Action Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Active File Switcher Pill
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        onClick = { fileMenuExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .testTag("active_file_switcher")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = uiState.activeFileName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (uiState.hasUnsavedChanges) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = fileMenuExpanded,
                        onDismissRequest = { fileMenuExpanded = false }
                    ) {
                        uiState.workspaceFiles.forEach { file ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = file.name,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = if (file.name == uiState.activeFileName) FontWeight.Bold else FontWeight.Normal,
                                        color = if (file.name == uiState.activeFileName) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    fileMenuExpanded = false
                                    onOpenFile(file.name)
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_new_file)) },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null)
                            },
                            onClick = {
                                fileMenuExpanded = false
                                onRequestNewFile()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Save Button
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.testTag("save_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = stringResource(R.string.action_save),
                        tint = if (uiState.hasUnsavedChanges) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Primary Run / Stop Button
                val isRunning = uiState.runStatus == RunStatus.RUNNING
                Button(
                    onClick = if (isRunning) onStop else onRun,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onTertiary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("run_script_button")
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isRunning) R.string.cd_stop_code else R.string.cd_run_code
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (isRunning) R.string.action_stop else R.string.action_run),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Adaptive Layout: Side-by-Side on Wide Screens, Stacked on Compact Screens
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isWide = maxWidth >= 720.dp
            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CodeEditorPanel(
                        code = uiState.editorCode,
                        fontSizeSp = settings.fontSizeSp,
                        tabSize = settings.tabSize,
                        wordWrap = settings.wordWrap,
                        onCodeChanged = onCodeChanged,
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutputConsolePanel(
                        entries = uiState.outputEntries,
                        runStatus = uiState.runStatus,
                        durationMs = uiState.lastRunDurationMs,
                        waitingPrompt = uiState.waitingForInputPrompt,
                        isExpanded = true,
                        fontSizeSp = settings.fontSizeSp,
                        showExpandToggle = false,
                        onToggleExpand = onToggleOutput,
                        onClear = onClearOutput,
                        onSubmitInput = onSubmitInput,
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CodeEditorPanel(
                        code = uiState.editorCode,
                        fontSizeSp = settings.fontSizeSp,
                        tabSize = settings.tabSize,
                        wordWrap = settings.wordWrap,
                        onCodeChanged = onCodeChanged,
                        modifier = Modifier
                            .weight(if (uiState.isOutputExpanded) 1.15f else 1f)
                            .fillMaxWidth()
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutputConsolePanel(
                        entries = uiState.outputEntries,
                        runStatus = uiState.runStatus,
                        durationMs = uiState.lastRunDurationMs,
                        waitingPrompt = uiState.waitingForInputPrompt,
                        isExpanded = uiState.isOutputExpanded,
                        fontSizeSp = settings.fontSizeSp,
                        showExpandToggle = true,
                        onToggleExpand = onToggleOutput,
                        onClear = onClearOutput,
                        onSubmitInput = onSubmitInput,
                        modifier = if (uiState.isOutputExpanded) {
                            Modifier
                                .weight(0.85f)
                                .fillMaxWidth()
                        } else {
                            Modifier
                                .height(48.dp)
                                .fillMaxWidth()
                        }
                    )
                }
            }
        }
    }
}
