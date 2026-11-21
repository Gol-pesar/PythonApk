package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EditorSettings
import com.example.data.PythonTemplate
import com.example.data.SettingsRepository
import com.example.data.WorkspaceFile
import com.example.data.WorkspaceRepository
import com.example.python.CPythonBridge
import com.example.python.CPythonCallback
import com.example.python.NativeRuntimeInfo
import com.example.runtime.RuntimeServiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    val workspacePath: String = "",
    val runtimeInfo: NativeRuntimeInfo = NativeRuntimeInfo(),
    val isBackgroundServiceActive: Boolean = false
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
    private val scriptExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "cpython-script-worker").apply { isDaemon = true }
    }
    private val replExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cpython-repl-worker").apply { isDaemon = true }
    }

    @Volatile
    private var activeRunId: String? = null

    @Volatile
    private var activeScriptFuture: Future<*>? = null

    @Volatile
    private var pendingScriptInput: CompletableDeferred<String>? = null

    @Volatile
    private var pendingReplInput: CompletableDeferred<String>? = null

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
                replEntries = initialReplBanner(NativeRuntimeInfo())
            )
        }

        RuntimeServiceController.onStopRequested = {
            stopActiveScript()
        }

        viewModelScope.launch {
            RuntimeServiceController.isServiceActive.collect { active ->
                _uiState.update { it.copy(isBackgroundServiceActive = active) }
            }
        }

        // Warm up native CPython runtime and load live package/version metadata off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = CPythonBridge.getRuntimeInfo(getApplication())
                _uiState.update { state ->
                    val updatedRepl = if (state.replEntries.size == 1 && state.replEntries.first().kind == ConsoleEntryKind.SYSTEM) {
                        initialReplBanner(info)
                    } else {
                        state.replEntries
                    }
                    state.copy(runtimeInfo = info, replEntries = updatedRepl)
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    private fun nextId(): Long = entryIdCounter++

    private fun initialReplBanner(info: NativeRuntimeInfo): List<ConsoleEntry> {
        val ver = info.pythonVersion
        val ws = info.websocketsVersion
        return listOf(
            ConsoleEntry(
                id = nextId(),
                text = "CPython $ver (Native Android Runtime via Chaquopy) • UTF-8\n" +
                    "PyPI packages ready: websockets ($ws), requests, certifi, asyncio, ssl, sqlite3\n" +
                    "Type any Python expression or statement below.",
                kind = ConsoleEntryKind.SYSTEM
            )
        )
    }

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
        val workspacePath = workspaceRepo.workspaceDir.absolutePath
        val runId = UUID.randomUUID().toString()
        activeRunId = runId

        val pyVer = _uiState.value.runtimeInfo.pythonVersion
        _uiState.update {
            it.copy(
                runStatus = RunStatus.RUNNING,
                isOutputExpanded = true,
                lastRunDurationMs = null,
                waitingForInputPrompt = null,
                outputEntries = listOf(
                    ConsoleEntry(
                        id = nextId(),
                        text = "$ python$pyVer $fileName",
                        kind = ConsoleEntryKind.SYSTEM
                    )
                )
            )
        }

        val appContext = getApplication<Application>().applicationContext
        RuntimeServiceController.start(appContext, fileName)

        val startTime = System.currentTimeMillis()
        activeScriptFuture = scriptExecutor.submit {
            val callback = object : CPythonCallback {
                override fun onOutput(text: String, isError: Boolean) {
                    if (activeRunId != runId) return
                    appendOutputEntry(
                        text = text,
                        kind = if (isError) ConsoleEntryKind.STDERR else ConsoleEntryKind.STDOUT
                    )
                }

                override fun onInput(prompt: String): String? {
                    if (activeRunId != runId) return null
                    val deferred = CompletableDeferred<String>()
                    pendingScriptInput = deferred
                    _uiState.update { it.copy(waitingForInputPrompt = prompt.ifEmpty { "ورودی:" }) }
                    return try {
                        val ans = runBlocking { deferred.await() }
                        pendingScriptInput = null
                        _uiState.update { it.copy(waitingForInputPrompt = null) }
                        appendOutputEntry("$ans\n", ConsoleEntryKind.INPUT_ECHO)
                        ans
                    } catch (_: Throwable) {
                        pendingScriptInput = null
                        _uiState.update { it.copy(waitingForInputPrompt = null) }
                        null
                    }
                }
            }

            try {
                val result = CPythonBridge.runScript(
                    context = appContext,
                    runId = runId,
                    code = code,
                    filename = fileName,
                    workspaceDir = workspacePath,
                    callback = callback
                )
                val duration = System.currentTimeMillis() - startTime
                if (activeRunId == runId) {
                    when (result.status) {
                        "ok" -> {
                            _uiState.update {
                                it.copy(
                                    runStatus = RunStatus.SUCCESS,
                                    lastRunDurationMs = duration,
                                    waitingForInputPrompt = null,
                                    workspaceFiles = workspaceRepo.listFiles()
                                )
                            }
                        }

                        "stopped" -> {
                            appendOutputEntry(
                                "\nKeyboardInterrupt: اجرا توسط کاربر متوقف شد.\n",
                                ConsoleEntryKind.STDERR
                            )
                            _uiState.update {
                                it.copy(
                                    runStatus = RunStatus.STOPPED,
                                    lastRunDurationMs = duration,
                                    waitingForInputPrompt = null
                                )
                            }
                        }

                        else -> {
                            if (!result.error.isNullOrBlank()) {
                                appendOutputEntry("\n${result.error}\n", ConsoleEntryKind.STDERR)
                            }
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
            } catch (t: Throwable) {
                val duration = System.currentTimeMillis() - startTime
                if (activeRunId == runId) {
                    appendOutputEntry(
                        "\nRuntimeError: ${t.message ?: "خطای ناشناخته در موتور CPython"}\n",
                        ConsoleEntryKind.STDERR
                    )
                    _uiState.update {
                        it.copy(
                            runStatus = RunStatus.ERROR,
                            lastRunDurationMs = duration,
                            waitingForInputPrompt = null
                        )
                    }
                }
            } finally {
                if (activeRunId == runId) {
                    activeRunId = null
                    activeScriptFuture = null
                    RuntimeServiceController.stop(appContext)
                }
            }
        }
    }

    fun stopActiveScript() {
        val runIdToStop = activeRunId
        pendingScriptInput?.cancel()
        pendingScriptInput = null

        val appContext = getApplication<Application>().applicationContext
        if (runIdToStop != null) {
            // Ask CPython to cleanly cancel asyncio event loops (releasing listening sockets) and interrupt thread.
            scriptExecutor.execute {
                CPythonBridge.stopScript(appContext, runIdToStop)
            }
        }
        RuntimeServiceController.stop(appContext)
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

        val appContext = getApplication<Application>().applicationContext
        val workspacePath = workspaceRepo.workspaceDir.absolutePath

        replExecutor.execute {
            val callback = object : CPythonCallback {
                override fun onOutput(text: String, isError: Boolean) {
                    appendReplEntry(
                        text = text.trimEnd('\n'),
                        kind = if (isError) ConsoleEntryKind.STDERR else ConsoleEntryKind.STDOUT
                    )
                }

                override fun onInput(prompt: String): String? {
                    val deferred = CompletableDeferred<String>()
                    pendingReplInput = deferred
                    _uiState.update { it.copy(replWaitingPrompt = prompt.ifEmpty { "ورودی:" }) }
                    return try {
                        val ans = runBlocking { deferred.await() }
                        pendingReplInput = null
                        _uiState.update { it.copy(replWaitingPrompt = null) }
                        appendReplEntry(ans, ConsoleEntryKind.INPUT_ECHO)
                        ans
                    } catch (_: Throwable) {
                        pendingReplInput = null
                        _uiState.update { it.copy(replWaitingPrompt = null) }
                        null
                    }
                }
            }

            try {
                val res = CPythonBridge.execRepl(
                    context = appContext,
                    code = cmd,
                    workspaceDir = workspacePath,
                    callback = callback
                )
                if (!res.resultRepr.isNullOrEmpty()) {
                    appendReplEntry(res.resultRepr, ConsoleEntryKind.RESULT)
                }
                if (!res.error.isNullOrBlank()) {
                    appendReplEntry(res.error.trimEnd(), ConsoleEntryKind.STDERR)
                }
                _uiState.update { it.copy(workspaceFiles = workspaceRepo.listFiles()) }
            } catch (t: Throwable) {
                appendReplEntry("RuntimeError: ${t.message ?: "خطا"}", ConsoleEntryKind.STDERR)
            }
        }
    }

    fun submitReplInput(input: String) {
        pendingReplInput?.complete(input)
    }

    fun clearReplConsole() {
        _uiState.update { it.copy(replEntries = initialReplBanner(it.runtimeInfo)) }
    }

    fun resetReplSession() {
        pendingReplInput?.cancel()
        pendingReplInput = null
        val appContext = getApplication<Application>().applicationContext
        replExecutor.execute {
            CPythonBridge.resetRepl(appContext)
        }
        _uiState.update {
            it.copy(
                replEntries = initialReplBanner(it.runtimeInfo) + ConsoleEntry(
                    id = nextId(),
                    text = "محیط متغیرهای کنسول CPython بازنشانی شد.",
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
        val initialText = "# $cleanName — Native CPython on Android\nprint(\"سلام از $cleanName!\")\n"
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
            // Coalesce consecutive stdout chunks and bound buffer so heavy logging stays smooth
            if (kind == ConsoleEntryKind.STDOUT && current.isNotEmpty() && current.last().kind == ConsoleEntryKind.STDOUT) {
                val combined = current.last().text + text
                val trimmed = if (combined.length > 32_768) combined.takeLast(32_768) else combined
                val merged = current.dropLast(1) + current.last().copy(text = trimmed)
                state.copy(outputEntries = merged)
            } else {
                state.copy(outputEntries = (current + ConsoleEntry(nextId(), text, kind)).takeLast(250))
            }
        }
    }

    private fun appendReplEntry(text: String, kind: ConsoleEntryKind) {
        _uiState.update { state ->
            state.copy(replEntries = (state.replEntries + ConsoleEntry(nextId(), text, kind)).takeLast(200))
        }
    }

    override fun onCleared() {
        stopActiveScript()
        RuntimeServiceController.onStopRequested = null
        scriptExecutor.shutdownNow()
        replExecutor.shutdownNow()
        super.onCleared()
    }
}
