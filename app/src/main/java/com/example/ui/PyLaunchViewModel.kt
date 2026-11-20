package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EditorSettings
import com.example.data.PythonTemplate
import com.example.data.SettingsRepository
import com.example.data.WorkspaceFile
import com.example.data.WorkspaceRepository
import com.example.python.PyEvaluator
import com.example.python.PyException
import com.example.python.PyExecutionCancelled
import com.example.python.PyNone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    EDITOR, CONSOLE, FILES, RUNTIME
}

enum class RunStatus {
    IDLE, RUNNING, SUCCESS, ERROR, STOPPED
}

enum class ConsoleEntryKind {
    PROMPT, STDOUT, STDERR, RESULT, SYSTEM, INPUT_ECHO
}

data class ConsoleEntry(
    val id: Long,
    val text: String,
    val kind: ConsoleEntryKind
)

data class PyLaunchUiState(
    val selectedTab: MainTab = MainTab.EDITOR,
    val activeFileName: String = "main.py",
    val editorCode: String = "",
    val hasUnsavedChanges: Boolean = false,
    val workspaceFiles: List<WorkspaceFile> = emptyList(),
    val templates: List<PythonTemplate> = emptyList(),
    val runStatus: RunStatus = RunStatus.IDLE,
    val lastRunDurationMs: Long? = null,
    val isOutputExpanded: Boolean = true,
    val outputEntries: List<ConsoleEntry> = emptyList(),
    val waitingForInputPrompt: String? = null,
    val replEntries: List<ConsoleEntry> = emptyList(),
    val replHistory: List<String> = emptyList(),
    val replWaitingPrompt: String? = null,
    val workspacePath: String = ""
)

class PyLaunchViewModel(application: Application) : AndroidViewModel(application) {

    private val workspaceRepo = WorkspaceRepository(application)
    private val settingsRepo = SettingsRepository(application)

    val settings: StateFlow<EditorSettings> = settingsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditorSettings()
    )

    private val _uiState = MutableStateFlow(PyLaunchUiState())
    val uiState: StateFlow<PyLaunchUiState> = _uiState.asStateFlow()

    private var entryIdCounter = 1L
    private var scriptJob: Job? = null
    private var pendingScriptInput: CompletableDeferred<String>? = null
    private var pendingReplInput: CompletableDeferred<String>? = null

    private var replEvaluator: PyEvaluator = createReplEvaluator()

    init {
        val files = workspaceRepo.listFiles()
        val initialFile = files.firstOrNull { it.name == "main.py" }?.name
            ?: files.firstOrNull()?.name
            ?: "main.py"
        val initialCode = workspaceRepo.readFile(initialFile)
        _uiState.update {
            it.copy(
                activeFileName = initialFile,
                editorCode = initialCode,
                workspaceFiles = files,
                templates = workspaceRepo.templates,
                workspacePath = workspaceRepo.workspaceDir.absolutePath,
                replEntries = initialReplBanner()
            )
        }
    }

    private fun nextId(): Long = entryIdCounter++

    private fun initialReplBanner(): List<ConsoleEntry> = listOf(
        ConsoleEntry(
            id = nextId(),
            text = "Python 3.15.2 (PyLaunch Android Runtime, UTF-8 Default) on android\n" +
                "Supports PEP 814 frozendict, PEP 810 lazy import, PEP 661 sentinel & PEP 798 unpacking.\n" +
                "Type any Python expression or statement below.",
            kind = ConsoleEntryKind.SYSTEM
        )
    )

    private fun createReplEvaluator(): PyEvaluator = PyEvaluator(
        workspaceDir = workspaceRepo.workspaceDir,
        onStdout = { chunk ->
            appendReplEntry(chunk, ConsoleEntryKind.STDOUT)
        },
        onInput = { prompt ->
            val deferred = CompletableDeferred<String>()
            pendingReplInput = deferred
            _uiState.update { it.copy(replWaitingPrompt = prompt.ifEmpty { "ورودی:" }) }
            val result = deferred.await()
            pendingReplInput = null
            _uiState.update { it.copy(replWaitingPrompt = null) }
            appendReplEntry(result, ConsoleEntryKind.INPUT_ECHO)
            result
        }
    )

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onCodeChanged(newCode: String) {
        _uiState.update {
            it.copy(
                editorCode = newCode,
                hasUnsavedChanges = true
            )
        }
    }

    fun saveActiveFile() {
        val state = _uiState.value
        workspaceRepo.writeFile(state.activeFileName, state.editorCode)
        _uiState.update {
            it.copy(
                hasUnsavedChanges = false,
                workspaceFiles = workspaceRepo.listFiles()
            )
        }
    }

    fun toggleOutputPanel() {
        _uiState.update { it.copy(isOutputExpanded = !it.isOutputExpanded) }
    }

    fun clearOutput() {
        _uiState.update {
            it.copy(
                outputEntries = emptyList(),
                runStatus = if (it.runStatus == RunStatus.RUNNING) RunStatus.RUNNING else RunStatus.IDLE,
                lastRunDurationMs = null
            )
        }
    }

    fun runActiveScript() {
        stopActiveScript()
        saveActiveFile()
        val fileName = _uiState.value.activeFileName
        val code = _uiState.value.editorCode

        _uiState.update {
            it.copy(
                runStatus = RunStatus.RUNNING,
                isOutputExpanded = true,
                lastRunDurationMs = null,
                waitingForInputPrompt = null,
                outputEntries = listOf(
                    ConsoleEntry(
                        id = nextId(),
                        text = "$ python3.15 $fileName",
                        kind = ConsoleEntryKind.SYSTEM
                    )
                )
            )
        }

        val startTime = System.currentTimeMillis()
        scriptJob = viewModelScope.launch {
            val evaluator = PyEvaluator(
                workspaceDir = workspaceRepo.workspaceDir,
                onStdout = { chunk ->
                    appendOutputEntry(chunk, ConsoleEntryKind.STDOUT)
                },
                onInput = { prompt ->
                    val deferred = CompletableDeferred<String>()
                    pendingScriptInput = deferred
                    _uiState.update { it.copy(waitingForInputPrompt = prompt.ifEmpty { "ورودی:" }) }
                    val ans = deferred.await()
                    pendingScriptInput = null
                    _uiState.update { it.copy(waitingForInputPrompt = null) }
                    appendOutputEntry("$ans\n", ConsoleEntryKind.INPUT_ECHO)
                    ans
                }
            )

            try {
                withContext(Dispatchers.Default) {
                    evaluator.executeScript(code, fileName)
                }
                val duration = System.currentTimeMillis() - startTime
                _uiState.update {
                    it.copy(
                        runStatus = RunStatus.SUCCESS,
                        lastRunDurationMs = duration,
                        workspaceFiles = workspaceRepo.listFiles()
                    )
                }
            } catch (_: PyExecutionCancelled) {
                val duration = System.currentTimeMillis() - startTime
                appendOutputEntry("\nKeyboardInterrupt: اجرا توسط کاربر متوقف شد.\n", ConsoleEntryKind.STDERR)
                _uiState.update {
                    it.copy(
                        runStatus = RunStatus.STOPPED,
                        lastRunDurationMs = duration,
                        waitingForInputPrompt = null
                    )
                }
            } catch (e: PyException) {
                val duration = System.currentTimeMillis() - startTime
                appendOutputEntry("\n${e.formatTraceback()}\n", ConsoleEntryKind.STDERR)
                _uiState.update {
                    it.copy(
                        runStatus = RunStatus.ERROR,
                        lastRunDurationMs = duration,
                        waitingForInputPrompt = null
                    )
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                appendOutputEntry("\nRuntimeError: ${e.message ?: "خطای ناشناخته"}\n", ConsoleEntryKind.STDERR)
                _uiState.update {
                    it.copy(
                        runStatus = RunStatus.ERROR,
                        lastRunDurationMs = duration,
                        waitingForInputPrompt = null
                    )
                }
            }
        }
    }

    fun stopActiveScript() {
        pendingScriptInput?.cancel()
        pendingScriptInput = null
        scriptJob?.cancel()
        scriptJob = null
        if (_uiState.value.runStatus == RunStatus.RUNNING) {
            _uiState.update {
                it.copy(
                    runStatus = RunStatus.STOPPED,
                    waitingForInputPrompt = null
                )
            }
        }
    }

    fun submitScriptInput(input: String) {
        pendingScriptInput?.complete(input)
    }

    fun executeReplCommand(rawCommand: String) {
        val cmd = rawCommand.trimEnd()
        if (cmd.isBlank()) return

        appendReplEntry(">>> $cmd", ConsoleEntryKind.PROMPT)
        _uiState.update { state ->
            val updatedHistory = (state.replHistory.filterNot { it == cmd } + cmd).takeLast(50)
            state.copy(replHistory = updatedHistory)
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    replEvaluator.executeRepl(cmd)
                }
                if (result != null && result !is PyNone) {
                    val repr = withContext(Dispatchers.Default) {
                        replEvaluator.pyToRepr(result)
                    }
                    appendReplEntry(repr, ConsoleEntryKind.RESULT)
                }
                _uiState.update { it.copy(workspaceFiles = workspaceRepo.listFiles()) }
            } catch (e: PyException) {
                appendReplEntry(e.formatTraceback(), ConsoleEntryKind.STDERR)
            } catch (e: Exception) {
                appendReplEntry("RuntimeError: ${e.message ?: "خطا"}", ConsoleEntryKind.STDERR)
            }
        }
    }

    fun submitReplInput(input: String) {
        pendingReplInput?.complete(input)
    }

    fun clearReplConsole() {
        _uiState.update { it.copy(replEntries = initialReplBanner()) }
    }

    fun resetReplSession() {
        pendingReplInput?.cancel()
        pendingReplInput = null
        replEvaluator = createReplEvaluator()
        _uiState.update {
            it.copy(
                replEntries = initialReplBanner() + ConsoleEntry(
                    id = nextId(),
                    text = "محیط متغیرهای کنسول بازنشانی شد.",
                    kind = ConsoleEntryKind.SYSTEM
                ),
                replWaitingPrompt = null
            )
        }
    }

    fun openFile(fileName: String) {
        if (_uiState.value.hasUnsavedChanges) {
            saveActiveFile()
        }
        val content = workspaceRepo.readFile(fileName)
        _uiState.update {
            it.copy(
                activeFileName = fileName,
                editorCode = content,
                hasUnsavedChanges = false,
                selectedTab = MainTab.EDITOR
            )
        }
    }

    fun createNewFile(rawName: String) {
        val cleanName = workspaceRepo.sanitizeName(rawName)
        val initialText = "# $cleanName — Python 3.15.2\nprint(\"سلام از $cleanName!\")\n"
        workspaceRepo.writeFile(cleanName, initialText)
        _uiState.update {
            it.copy(
                activeFileName = cleanName,
                editorCode = initialText,
                hasUnsavedChanges = false,
                workspaceFiles = workspaceRepo.listFiles(),
                selectedTab = MainTab.EDITOR
            )
        }
    }

    fun openTemplate(template: PythonTemplate, autoRun: Boolean = false) {
        workspaceRepo.writeFile(template.fileName, template.code)
        _uiState.update {
            it.copy(
                activeFileName = template.fileName,
                editorCode = template.code,
                hasUnsavedChanges = false,
                workspaceFiles = workspaceRepo.listFiles(),
                selectedTab = MainTab.EDITOR
            )
        }
        if (autoRun) {
            runActiveScript()
        }
    }

    fun renameFile(oldName: String, newName: String) {
        val renamed = workspaceRepo.renameFile(oldName, newName)
        val currentActive = _uiState.value.activeFileName
        _uiState.update {
            it.copy(
                activeFileName = if (currentActive == oldName) renamed else currentActive,
                workspaceFiles = workspaceRepo.listFiles()
            )
        }
    }

    fun deleteFile(name: String) {
        workspaceRepo.deleteFile(name)
        val remaining = workspaceRepo.listFiles()
        if (_uiState.value.activeFileName == name) {
            val fallback = remaining.firstOrNull()
            if (fallback != null) {
                _uiState.update {
                    it.copy(
                        activeFileName = fallback.name,
                        editorCode = workspaceRepo.readFile(fallback.name),
                        hasUnsavedChanges = false,
                        workspaceFiles = remaining
                    )
                }
            } else {
                createNewFile("main.py")
            }
        } else {
            _uiState.update { it.copy(workspaceFiles = remaining) }
        }
    }

    fun updateDarkTheme(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setDarkTheme(enabled) }
    }

    fun updateFontSize(sizeSp: Int) {
        viewModelScope.launch { settingsRepo.setFontSize(sizeSp) }
    }

    fun updateTabSize(spaces: Int) {
        viewModelScope.launch { settingsRepo.setTabSize(spaces) }
    }

    fun updateWordWrap(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setWordWrap(enabled) }
    }

    private fun appendOutputEntry(text: String, kind: ConsoleEntryKind) {
        _uiState.update { state ->
            val current = state.outputEntries
            // Coalesce consecutive stdout chunks for crisp terminal display
            if (kind == ConsoleEntryKind.STDOUT && current.isNotEmpty() && current.last().kind == ConsoleEntryKind.STDOUT) {
                val merged = current.dropLast(1) + current.last().copy(text = current.last().text + text)
                state.copy(outputEntries = merged)
            } else {
                state.copy(outputEntries = current + ConsoleEntry(nextId(), text, kind))
            }
        }
    }

    private fun appendReplEntry(text: String, kind: ConsoleEntryKind) {
        _uiState.update { state ->
            state.copy(replEntries = (state.replEntries + ConsoleEntry(nextId(), text, kind)).takeLast(200))
        }
    }
}
