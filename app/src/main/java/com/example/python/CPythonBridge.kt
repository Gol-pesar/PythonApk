package com.example.python

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

interface CPythonCallback {
    fun onOutput(text: String, isError: Boolean)
    fun onInput(prompt: String): String?
}

data class CPythonScriptResult(
    val status: String,
    val error: String? = null
)

data class CPythonReplResult(
    val resultRepr: String? = null,
    val error: String? = null
)

data class NativeRuntimeInfo(
    val pythonVersion: String = "3.11",
    val fullVersion: String = "CPython 3.11 (Chaquopy Native)",
    val implementation: String = "CPython",
    val websocketsVersion: String = "installed",
    val requestsVersion: String = "installed",
    val certifiVersion: String = "installed",
    val opensslVersion: String = "OpenSSL",
    val packages: List<String> = emptyList(),
    val isReady: Boolean = false
)

object CPythonBridge {

    private val initLock = Any()

    private fun ensureStarted(context: Context): Python {
        if (!Python.isStarted()) {
            synchronized(initLock) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
            }
        }
        return Python.getInstance()
    }

    private fun runnerModule(context: Context): PyObject {
        return ensureStarted(context).getModule("pylaunch_runner")
    }

    fun runScript(
        context: Context,
        runId: String,
        code: String,
        filename: String,
        workspaceDir: String,
        callback: CPythonCallback
    ): CPythonScriptResult {
        val raw = runnerModule(context)
            .callAttr("run_script", runId, code, filename, workspaceDir, callback)
            .toString()
        val json = JSONObject(raw)
        val status = json.optString("status", "ok")
        val error = if (json.isNull("error")) null else json.optString("error")
        return CPythonScriptResult(status = status, error = error)
    }

    fun stopScript(context: Context, runId: String): Boolean {
        if (!Python.isStarted()) return false
        return try {
            runnerModule(context).callAttr("stop_script", runId).toBoolean()
        } catch (_: Exception) {
            false
        }
    }

    fun execRepl(
        context: Context,
        code: String,
        workspaceDir: String,
        callback: CPythonCallback
    ): CPythonReplResult {
        val raw = runnerModule(context)
            .callAttr("exec_repl", code, workspaceDir, callback)
            .toString()
        val json = JSONObject(raw)
        val result = if (json.isNull("result")) null else json.optString("result")
        val error = if (json.isNull("error")) null else json.optString("error")
        return CPythonReplResult(resultRepr = result, error = error)
    }

    fun resetRepl(context: Context) {
        if (!Python.isStarted()) return
        try {
            runnerModule(context).callAttr("reset_repl")
        } catch (_: Exception) {
        }
    }

    fun getRuntimeInfo(context: Context): NativeRuntimeInfo {
        val raw = runnerModule(context).callAttr("get_runtime_info").toString()
        val json = JSONObject(raw)
        val pkgsArray = json.optJSONArray("packages")
        val pkgs = buildList {
            if (pkgsArray != null) {
                for (i in 0 until pkgsArray.length()) {
                    add(pkgsArray.optString(i))
                }
            }
        }
        return NativeRuntimeInfo(
            pythonVersion = json.optString("python_version", "3.11"),
            fullVersion = json.optString("full_version", "CPython 3.11"),
            implementation = json.optString("implementation", "CPython"),
            websocketsVersion = json.optString("websockets_version", "installed"),
            requestsVersion = json.optString("requests_version", "installed"),
            certifiVersion = json.optString("certifi_version", "installed"),
            opensslVersion = json.optString("openssl_version", "OpenSSL"),
            packages = pkgs,
            isReady = true
        )
    }
}
