package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.FilesScreen
import com.example.ui.screens.ReplScreen
import com.example.ui.screens.RuntimeScreen
import com.example.ui.theme.PyLaunchTheme

private data class NavDestination(
    val tab: MainTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val Destinations = listOf(
    NavDestination(MainTab.EDITOR, R.string.nav_editor, Icons.Filled.Code, Icons.Outlined.Code, "nav_editor"),
    NavDestination(MainTab.CONSOLE, R.string.nav_console, Icons.Filled.Terminal, Icons.Outlined.Terminal, "nav_console"),
    NavDestination(MainTab.FILES, R.string.nav_files, Icons.Filled.Folder, Icons.Outlined.Folder, "nav_files"),
    NavDestination(MainTab.RUNTIME, R.string.nav_runtime, Icons.Filled.Memory, Icons.Outlined.Memory, "nav_runtime")
)

@Composable
fun PyLaunchApp(
    viewModel: PyLaunchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var askedNotificationPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.runActiveScript()
    }

    val runWithNotificationSupport: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotificationPermission &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.runActiveScript()
        }
    }

    // Return to Editor tab when pressing system Back from secondary tabs
    BackHandler(enabled = uiState.selectedTab != MainTab.EDITOR) {
        viewModel.selectTab(MainTab.EDITOR)
    }

    PyLaunchTheme(darkTheme = settings.isDarkTheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 680.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!useNavigationRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 4.dp
                            ) {
                                Destinations.forEach { dest ->
                                    val selected = uiState.selectedTab == dest.tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(dest.tab) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        modifier = Modifier.testTag(dest.testTag)
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavigationRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                Destinations.forEach { dest ->
                                    val selected = uiState.selectedTab == dest.tab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(dest.tab) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        modifier = Modifier.testTag(dest.testTag)
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            when (uiState.selectedTab) {
                                MainTab.EDITOR -> EditorScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    onCodeChanged = viewModel::onCodeChanged,
                                    onSave = viewModel::saveActiveFile,
                                    onRun = runWithNotificationSupport,
                                    onStop = viewModel::stopActiveScript,
                                    onOpenFile = viewModel::openFile,
                                    onRequestNewFile = {
                                        newFileName = ""
                                        showNewFileDialog = true
                                    },
                                    onToggleOutput = viewModel::toggleOutputPanel,
                                    onClearOutput = viewModel::clearOutput,
                                    onSubmitInput = viewModel::submitScriptInput
                                )

                                MainTab.CONSOLE -> ReplScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    onExecuteCommand = viewModel::executeReplCommand,
                                    onSubmitInput = viewModel::submitReplInput,
                                    onClear = viewModel::clearReplConsole,
                                    onReset = viewModel::resetReplSession
                                )

                                MainTab.FILES -> FilesScreen(
                                    uiState = uiState,
                                    onOpenFile = viewModel::openFile,
                                    onRequestNewFile = {
                                        newFileName = ""
                                        showNewFileDialog = true
                                    },
                                    onRenameFile = viewModel::renameFile,
                                    onDeleteFile = viewModel::deleteFile,
                                    onOpenTemplate = viewModel::openTemplate
                                )

                                MainTab.RUNTIME -> RuntimeScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    onTryInRepl = { snippet ->
                                        viewModel.selectTab(MainTab.CONSOLE)
                                        viewModel.executeReplCommand(snippet)
                                    },
                                    onDarkThemeChanged = viewModel::updateDarkTheme,
                                    onFontSizeChanged = viewModel::updateFontSize,
                                    onTabSizeChanged = viewModel::updateTabSize,
                                    onWordWrapChanged = viewModel::updateWordWrap
                                )
                            }
                        }
                    }
                }
            }

            if (showNewFileDialog) {
                AlertDialog(
                    onDismissRequest = { showNewFileDialog = false },
                    title = { Text(stringResource(R.string.dialog_new_file_title)) },
                    text = {
                        OutlinedTextField(
                            value = newFileName,
                            onValueChange = { newFileName = it },
                            label = { Text(stringResource(R.string.dialog_file_name_label)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("new_file_name_input")
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newFileName.isNotBlank()) {
                                    viewModel.createNewFile(newFileName)
                                }
                                showNewFileDialog = false
                            },
                            modifier = Modifier.testTag("confirm_new_file_button")
                        ) {
                            Text(stringResource(R.string.action_create))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewFileDialog = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }
}
