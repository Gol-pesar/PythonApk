package com.example.python

import java.io.File
import java.math.BigInteger
import kotlin.math.floor

sealed class PyValue {
    abstract val typeName: String
    open fun toBool(): Boolean = true
    open fun toDisplayString(): String = toReprString()
    abstract fun toReprString(): String

    open fun pyEquals(other: PyValue): Boolean = this === other

    open fun pyHash(): Int = System.identityHashCode(this)

    open fun asDouble(): Double? = null
    open fun asBigInt(): BigInteger? = null
}

data object PyNone : PyValue() {
    override val typeName: String = "NoneType"
    override fun toBool(): Boolean = false
    override fun toReprString(): String = "None"
    override fun pyEquals(other: PyValue): Boolean = other is PyNone
    override fun pyHash(): Int = 0
}

class PyBool private constructor(val value: Boolean) : PyValue() {
    override val typeName: String = "bool"
    override fun toBool(): Boolean = value
    override fun toReprString(): String = if (value) "True" else "False"
    override fun asBigInt(): BigInteger = if (value) BigInteger.ONE else BigInteger.ZERO
    override fun asDouble(): Double = if (value) 1.0 else 0.0
    override fun pyEquals(other: PyValue): Boolean = when (other) {
        is PyBool -> value == other.value
        is PyInt -> asBigInt() == other.value
        is PyFloat -> asDouble() == other.value
        else -> false
    }
    override fun pyHash(): Int = if (value) 1 else 0

    companion object {
        val TRUE = PyBool(true)
        val FALSE = PyBool(false)
        fun of(v: Boolean): PyBool = if (v) TRUE else FALSE
    }
}

class PyInt(val value: BigInteger) : PyValue() {
    constructor(v: Long) : this(BigInteger.valueOf(v))
    constructor(v: Int) : this(BigInteger.valueOf(v.toLong()))

    override val typeName: String = "int"
    override fun toBool(): Boolean = value != BigInteger.ZERO
    override fun toReprString(): String = value.toString()
    override fun asBigInt(): BigInteger = value
    override fun asDouble(): Double = value.toDouble()
    override fun pyEquals(other: PyValue): Boolean = when (other) {
        is PyInt -> value == other.value
        is PyBool -> value == other.asBigInt()
        is PyFloat -> value.toDouble() == other.value && floor(other.value) == other.value
        else -> false
    }
    override fun pyHash(): Int = value.hashCode()
}

class PyFloat(val value: Double) : PyValue() {
    override val typeName: String = "float"
    override fun toBool(): Boolean = value != 0.0
    override fun toReprString(): String {
        if (value.isNaN()) return "nan"
        if (value == Double.POSITIVE_INFINITY) return "inf"
        if (value == Double.NEGATIVE_INFINITY) return "-inf"
        val s = value.toString()
        return if (!s.contains('.') && !s.contains('e') && !s.contains('E')) "$s.0" else s
    }
    override fun asDouble(): Double = value
    override fun pyEquals(other: PyValue): Boolean = when (other) {
        is PyFloat -> value == other.value
        is PyInt -> value == other.value.toDouble() && floor(value) == value
        is PyBool -> value == other.asDouble()
        else -> false
    }
    override fun pyHash(): Int = value.hashCode()
}

class PyStr(val value: String) : PyValue() {
    override val typeName: String = "str"
    override fun toBool(): Boolean = value.isNotEmpty()
    override fun toDisplayString(): String = value
    override fun toReprString(): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("'", "\\'")
        return "'$escaped'"
    }
    override fun pyEquals(other: PyValue): Boolean = other is PyStr && value == other.value
    override fun pyHash(): Int = value.hashCode()
}

class PyList(val elements: MutableList<PyValue>) : PyValue() {
    override val typeName: String = "list"
    override fun toBool(): Boolean = elements.isNotEmpty()
    override fun toReprString(): String =
        elements.joinToString(", ", prefix = "[", postfix = "]") { it.toReprString() }
    override fun pyEquals(other: PyValue): Boolean {
        if (other !is PyList || elements.size != other.elements.size) return false
        return elements.indices.all { elements[it].pyEquals(other.elements[it]) }
    }
}

class PyTuple(val elements: List<PyValue>) : PyValue() {
    override val typeName: String = "tuple"
    override fun toBool(): Boolean = elements.isNotEmpty()
    override fun toReprString(): String = when (elements.size) {
        0 -> "()"
        1 -> "(${elements[0].toReprString()},)"
        else -> elements.joinToString(", ", prefix = "(", postfix = ")") { it.toReprString() }
    }
    override fun pyEquals(other: PyValue): Boolean {
        if (other !is PyTuple || elements.size != other.elements.size) return false
        return elements.indices.all { elements[it].pyEquals(other.elements[it]) }
    }
    override fun pyHash(): Int = elements.fold(17) { acc, v -> 31 * acc + v.pyHash() }
}

class PyKey(val value: PyValue) {
    override fun equals(other: Any?): Boolean = other is PyKey && value.pyEquals(other.value)
    override fun hashCode(): Int = value.pyHash()
}

class PyDict(val entries: LinkedHashMap<PyKey, PyValue> = linkedMapOf()) : PyValue() {
    override val typeName: String = "dict"
    override fun toBool(): Boolean = entries.isNotEmpty()
    override fun toReprString(): String =
        entries.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
            "${k.value.toReprString()}: ${v.toReprString()}"
        }
    fun get(key: PyValue): PyValue? = entries[PyKey(key)]
    fun set(key: PyValue, value: PyValue) {
        ensureHashable(key)
        entries[PyKey(key)] = value
    }
    fun containsKey(key: PyValue): Boolean = entries.containsKey(PyKey(key))
    override fun pyEquals(other: PyValue): Boolean {
        val otherEntries = when (other) {
            is PyDict -> other.entries
            is PyFrozenDict -> other.entries
            else -> return false
        }
        if (entries.size != otherEntries.size) return false
        return entries.all { (k, v) ->
            val ov = otherEntries[k]
            ov != null && v.pyEquals(ov)
        }
    }
}

/**
 * Python 3.15 PEP 814 built-in immutable mapping type: frozendict
 */
class PyFrozenDict(val entries: LinkedHashMap<PyKey, PyValue>) : PyValue() {
    override val typeName: String = "frozendict"
    override fun toBool(): Boolean = entries.isNotEmpty()
    override fun toReprString(): String {
        val inner = entries.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
            "${k.value.toReprString()}: ${v.toReprString()}"
        }
        return "frozendict($inner)"
    }
    fun get(key: PyValue): PyValue? = entries[PyKey(key)]
    fun containsKey(key: PyValue): Boolean = entries.containsKey(PyKey(key))
    override fun pyEquals(other: PyValue): Boolean {
        val otherEntries = when (other) {
            is PyFrozenDict -> other.entries
            is PyDict -> other.entries
            else -> return false
        }
        if (entries.size != otherEntries.size) return false
        return entries.all { (k, v) ->
            val ov = otherEntries[k]
            ov != null && v.pyEquals(ov)
        }
    }
    override fun pyHash(): Int = entries.entries.fold(0) { acc, (k, v) ->
        acc xor (k.hashCode() * 31 + v.pyHash())
    }
}

class PySet(val elements: LinkedHashSet<PyKey> = linkedSetOf()) : PyValue() {
    override val typeName: String = "set"
    override fun toBool(): Boolean = elements.isNotEmpty()
    override fun toReprString(): String =
        if (elements.isEmpty()) "set()"
        else elements.joinToString(", ", prefix = "{", postfix = "}") { it.value.toReprString() }
    override fun pyEquals(other: PyValue): Boolean {
        if (other !is PySet || elements.size != other.elements.size) return false
        return elements.all { other.elements.contains(it) }
    }
}

/**
 * Python 3.15 PEP 661 Sentinel object: sentinel('MISSING')
 */
class PySentinel(val name: String, val reprText: String = "<$name>") : PyValue() {
    override val typeName: String = "Sentinel"
    override fun toReprString(): String = reprText
    override fun pyEquals(other: PyValue): Boolean = this === other
}

class PyRange(val start: Long, val stop: Long, val step: Long = 1L) : PyValue() {
    override val typeName: String = "range"
    val size: Long = when {
        step > 0 && start < stop -> (stop - start - 1) / step + 1
        step < 0 && start > stop -> (start - stop - 1) / (-step) + 1
        else -> 0L
    }
    override fun toBool(): Boolean = size > 0
    override fun toReprString(): String =
        if (step == 1L) "range($start, $stop)" else "range($start, $stop, $step)"

    fun toList(): List<PyInt> {
        val out = ArrayList<PyInt>(size.coerceAtMost(1_000_000L).toInt())
        var cur = start
        if (step > 0) {
            while (cur < stop) {
                out.add(PyInt(cur))
                cur += step
            }
        } else {
            while (cur > stop) {
                out.add(PyInt(cur))
                cur += step
            }
        }
        return out
    }

    fun contains(v: Long): Boolean {
        if (size == 0L) return false
        return if (step > 0) v in start until stop && (v - start) % step == 0L
        else v in (stop + 1)..start && (start - v) % (-step) == 0L
    }
}

class PySlice(val start: Int?, val stop: Int?, val step: Int?) : PyValue() {
    override val typeName: String = "slice"
    override fun toReprString(): String = "slice(${start ?: "None"}, ${stop ?: "None"}, ${step ?: "None"})"
}

class PyFunction(
    val name: String,
    val params: List<PyParam>,
    val body: List<Stmt>,
    val closure: PyEnvironment,
    val isGenerator: Boolean = false,
    val defaults: Map<String, PyValue> = emptyMap(),
    val doc: String? = null
) : PyValue() {
    override val typeName: String = "function"
    override fun toReprString(): String = "<function $name>"
}

class PyNativeFunction(
    val name: String,
    val invoke: suspend (evaluator: PyEvaluator, args: List<PyValue>, kwargs: Map<String, PyValue>) -> PyValue
) : PyValue() {
    override val typeName: String = "builtin_function_or_method"
    override fun toReprString(): String = "<built-in function $name>"
}

class PyClass(
    val name: String,
    val bases: List<PyClass>,
    val attributes: MutableMap<String, PyValue> = linkedMapOf()
) : PyValue() {
    override val typeName: String = "type"
    override fun toReprString(): String = "<class '$name'>"

    fun lookupAttribute(attrName: String): PyValue? {
        attributes[attrName]?.let { return it }
        for (base in bases) {
            base.lookupAttribute(attrName)?.let { return it }
        }
        return null
    }

    fun isSubclassOf(other: PyClass): Boolean {
        if (this === other || this.name == other.name) return true
        return bases.any { it.isSubclassOf(other) }
    }
}

class PyInstance(
    val pyClass: PyClass,
    val fields: MutableMap<String, PyValue> = linkedMapOf()
) : PyValue() {
    override val typeName: String get() = pyClass.name
    override fun toReprString(): String = "<${pyClass.name} object>"
}

class PyBoundMethod(
    val receiver: PyValue,
    val function: PyValue
) : PyValue() {
    override val typeName: String = "method"
    override fun toReprString(): String = "<bound method of ${receiver.typeName}>"
}

class PyProperty(
    val getter: PyValue,
    val setter: PyValue? = null
) : PyValue() {
    override val typeName: String = "property"
    override fun toReprString(): String = "<property object>"
}

class PyStaticMethod(val function: PyValue) : PyValue() {
    override val typeName: String = "staticmethod"
    override fun toReprString(): String = "<staticmethod>"
}

class PyClassMethod(val function: PyValue) : PyValue() {
    override val typeName: String = "classmethod"
    override fun toReprString(): String = "<classmethod>"
}

open class PyModule(
    val name: String,
    val members: MutableMap<String, PyValue> = linkedMapOf()
) : PyValue() {
    override val typeName: String = "module"
    override fun toReprString(): String = "<module '$name'>"
}

/**
 * Python 3.15 PEP 810 Explicit Lazy Import proxy.
 * Resolves the underlying module on first attribute access!
 */
class PyLazyModule(
    val moduleName: String,
    private val loader: suspend () -> PyModule
) : PyValue() {
    var resolved: PyModule? = null
        private set

    override val typeName: String = "module"
    override fun toReprString(): String =
        if (resolved == null) "<lazy module '$moduleName' (not yet loaded)>"
        else "<module '$moduleName'>"

    suspend fun resolve(): PyModule {
        resolved?.let { return it }
        val loaded = loader()
        resolved = loaded
        return loaded
    }
}

class PyIterator(
    private val items: List<PyValue>,
    private val label: String = "iterator"
) : PyValue() {
    private var index = 0
    override val typeName: String = label
    override fun toReprString(): String = "<$label object>"
    fun hasNext(): Boolean = index < items.size
    fun next(): PyValue {
        if (index >= items.size) throw PyException("StopIteration", "")
        return items[index++]
    }
    fun remaining(): List<PyValue> {
        val rest = items.subList(index, items.size).toList()
        index = items.size
        return rest
    }
}

class PyFile(
    val file: File,
    val mode: String,
    val encoding: String = "utf-8"
) : PyValue() {
    var isClosed: Boolean = false
    private var readLinesCache: List<String>? = null
    private var lineIndex: Int = 0

    override val typeName: String = "TextIOWrapper"
    override fun toReprString(): String =
        "<_io.TextIOWrapper name='${file.name}' mode='$mode' encoding='$encoding'>"

    fun readAll(): String {
        checkOpen()
        if (!file.exists()) throw PyException("FileNotFoundError", "No such file or directory: '${file.name}'")
        return file.readText(Charsets.UTF_8)
    }

    fun readLines(): List<String> {
        checkOpen()
        if (!file.exists()) throw PyException("FileNotFoundError", "No such file or directory: '${file.name}'")
        val text = file.readText(Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        return text.lines().mapIndexed { idx, line ->
            if (idx < text.lines().lastIndex) "$line\n" else line
        }
    }

    fun readLine(): String {
        checkOpen()
        if (readLinesCache == null) {
            readLinesCache = readLines()
        }
        val lines = readLinesCache!!
        return if (lineIndex < lines.size) lines[lineIndex++] else ""
    }

    fun write(content: String): Int {
        checkOpen()
        file.parentFile?.mkdirs()
        if (mode.contains('a')) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.appendText(content, Charsets.UTF_8)
        }
        return content.length
    }

    fun close() {
        isClosed = true
    }

    private fun checkOpen() {
        if (isClosed) throw PyException("ValueError", "I/O operation on closed file.")
    }
}

fun ensureHashable(value: PyValue) {
    when (value) {
        is PyList, is PyDict, is PySet ->
            throw PyException("TypeError", "unhashable type: '${value.typeName}'")
        is PyTuple -> value.elements.forEach { ensureHashable(it) }
        else -> Unit
    }
}
