package com.example.python

data class PyStackFrame(
    val fileName: String,
    val line: Int,
    val functionName: String
)

open class PyException(
    val typeName: String,
    val pyMessage: String,
    val line: Int = 1,
    val fileName: String = "<main>",
    val frames: MutableList<PyStackFrame> = mutableListOf()
) : RuntimeException("$typeName: $pyMessage") {

    fun addFrame(fileName: String, line: Int, functionName: String) {
        frames.add(PyStackFrame(fileName, line, functionName))
    }

    fun formatTraceback(): String {
        val sb = StringBuilder()
        sb.appendLine("Traceback (most recent call last):")
        if (frames.isEmpty()) {
            sb.appendLine("  File \"$fileName\", line $line, in <module>")
        } else {
            for (frame in frames.asReversed()) {
                sb.appendLine("  File \"${frame.fileName}\", line ${frame.line}, in ${frame.functionName}")
            }
        }
        if (pyMessage.isNotEmpty()) {
            sb.append("$typeName: $pyMessage")
        } else {
            sb.append(typeName)
        }
        return sb.toString()
    }
}

class PySyntaxError(
    message: String,
    line: Int,
    fileName: String = "<main>"
) : PyException("SyntaxError", message, line, fileName)

class PyExecutionCancelled : RuntimeException("KeyboardInterrupt: اجرا توسط کاربر متوقف شد")

internal class ReturnSignal(val value: PyValue) : RuntimeException()
internal class BreakSignal : RuntimeException()
internal class ContinueSignal : RuntimeException()
internal class YieldSignal(val value: PyValue) : RuntimeException()
