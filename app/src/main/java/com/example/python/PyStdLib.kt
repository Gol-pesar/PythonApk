package com.example.python

import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.math.*
import kotlin.random.Random

object PyStdLib {

    val availableModules: List<String> = listOf(
        "sys", "os", "os.path", "math", "random", "json", "datetime",
        "time", "re", "collections", "itertools", "functools", "statistics",
        "string", "hashlib", "base64", "pathlib", "profiling", "sentinels", "typing"
    )

    fun loadModule(name: String, workspaceDir: File, evaluator: PyEvaluator): PyModule? {
        return when (name) {
            "sys" -> createSysModule(workspaceDir)
            "os" -> createOsModule(workspaceDir)
            "os.path" -> createOsPathModule(workspaceDir)
            "math" -> createMathModule()
            "random" -> createRandomModule()
            "json" -> createJsonModule()
            "datetime" -> createDatetimeModule()
            "time" -> createTimeModule()
            "re" -> createReModule()
            "collections" -> createCollectionsModule()
            "itertools" -> createItertoolsModule()
            "functools" -> createFunctoolsModule()
            "statistics" -> createStatisticsModule()
            "string" -> createStringModule()
            "hashlib" -> createHashlibModule()
            "base64" -> createBase64Module()
            "pathlib" -> createPathlibModule(workspaceDir)
            "profiling", "profiling.sampling" -> createProfilingModule()
            "sentinels" -> createSentinelsModule()
            "typing" -> createTypingModule()
            else -> loadWorkspaceModule(name, workspaceDir, evaluator)
        }
    }

    private fun loadWorkspaceModule(name: String, workspaceDir: File, evaluator: PyEvaluator): PyModule? {
        val relPath = name.replace('.', '/') + ".py"
        val file = File(workspaceDir, relPath)
        if (!file.exists() || !file.isFile) return null
        val source = file.readText(Charsets.UTF_8)
        val modEnv = PyEnvironment(parent = evaluator.globalEnv, isModuleRoot = true)
        modEnv.defineLocal("__name__", PyStr(name))
        modEnv.defineLocal("__file__", PyStr(file.name))
        val tokens = PyLexer(source, file.name).tokenize()
        val stmts = PyParser(tokens, file.name).parseProgram()
        kotlinx.coroutines.runBlocking {
            for (s in stmts) evaluator.execStmt(s, modEnv)
        }
        return PyModule(name, modEnv.values)
    }

    private fun createSysModule(workspaceDir: File): PyModule {
        val mod = PyModule("sys")
        mod.members["version"] = PyStr("3.15.2 (PyLaunch Android Runtime, PEP 686 UTF-8) [Android API ${Build.VERSION.SDK_INT}]")
        mod.members["version_info"] = PyTuple(listOf(PyInt(3), PyInt(15), PyInt(2), PyStr("final"), PyInt(0)))
        mod.members["platform"] = PyStr("android")
        mod.members["byteorder"] = PyStr("little")
        mod.members["maxsize"] = PyInt(Long.MAX_VALUE)
        mod.members["path"] = PyList(mutableListOf(PyStr(workspaceDir.absolutePath)))
        mod.members["argv"] = PyList(mutableListOf(PyStr("main.py")))
        mod.members["getdefaultencoding"] = PyNativeFunction("getdefaultencoding") { _, _, _ -> PyStr("utf-8") }
        mod.members["getfilesystemencoding"] = PyNativeFunction("getfilesystemencoding") { _, _, _ -> PyStr("utf-8") }
        mod.members["exit"] = PyNativeFunction("exit") { _, args, _ ->
            val msg = args.firstOrNull()?.toDisplayString() ?: "0"
            throw PyException("SystemExit", msg)
        }
        return mod
    }

    private fun createOsModule(workspaceDir: File): PyModule {
        val mod = PyModule("os")
        mod.members["name"] = PyStr("posix")
        mod.members["sep"] = PyStr("/")
        mod.members["linesep"] = PyStr("\n")
        mod.members["path"] = createOsPathModule(workspaceDir)
        mod.members["getcwd"] = PyNativeFunction("getcwd") { _, _, _ ->
            PyStr(workspaceDir.absolutePath)
        }
        mod.members["listdir"] = PyNativeFunction("listdir") { _, args, _ ->
            val dir = if (args.isEmpty()) workspaceDir
            else PyBuiltins.resolveSandboxedFile(workspaceDir, (args[0] as? PyStr)?.value ?: ".")
            val files = dir.listFiles()?.map { PyStr(it.name) }?.sortedBy { it.value } ?: emptyList()
            PyList(files.toMutableList())
        }
        mod.members["makedirs"] = PyNativeFunction("makedirs") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: throw PyException("TypeError", "makedirs expects path str")
            PyBuiltins.resolveSandboxedFile(workspaceDir, p).mkdirs()
            PyNone
        }
        mod.members["remove"] = PyNativeFunction("remove") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: throw PyException("TypeError", "remove expects path str")
            val f = PyBuiltins.resolveSandboxedFile(workspaceDir, p)
            if (!f.exists()) throw PyException("FileNotFoundError", "No such file: '$p'")
            f.delete()
            PyNone
        }
        return mod
    }

    private fun createOsPathModule(workspaceDir: File): PyModule {
        val mod = PyModule("os.path")
        mod.members["exists"] = PyNativeFunction("exists") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: return@PyNativeFunction PyBool.FALSE
            PyBool.of(PyBuiltins.resolveSandboxedFile(workspaceDir, p).exists())
        }
        mod.members["isfile"] = PyNativeFunction("isfile") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: return@PyNativeFunction PyBool.FALSE
            PyBool.of(PyBuiltins.resolveSandboxedFile(workspaceDir, p).isFile)
        }
        mod.members["isdir"] = PyNativeFunction("isdir") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: return@PyNativeFunction PyBool.FALSE
            PyBool.of(PyBuiltins.resolveSandboxedFile(workspaceDir, p).isDirectory)
        }
        mod.members["join"] = PyNativeFunction("join") { _, args, _ ->
            PyStr(args.joinToString("/") { it.toDisplayString().trim('/') })
        }
        mod.members["basename"] = PyNativeFunction("basename") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: ""
            PyStr(File(p).name)
        }
        mod.members["getsize"] = PyNativeFunction("getsize") { _, args, _ ->
            val p = (args.firstOrNull() as? PyStr)?.value ?: throw PyException("TypeError", "getsize expects path")
            val f = PyBuiltins.resolveSandboxedFile(workspaceDir, p)
            if (!f.exists()) throw PyException("FileNotFoundError", "No such file: '$p'")
            PyInt(f.length())
        }
        return mod
    }

    private fun createMathModule(): PyModule {
        val mod = PyModule("math")
        mod.members["pi"] = PyFloat(PI)
        mod.members["e"] = PyFloat(E)
        mod.members["tau"] = PyFloat(2.0 * PI)
        mod.members["inf"] = PyFloat(Double.POSITIVE_INFINITY)
        mod.members["nan"] = PyFloat(Double.NaN)

        fun num1(name: String, fn: (Double) -> Double) = PyNativeFunction(name) { _, args, _ ->
            PyBuiltins.requireArgs(name, args, 1)
            val d = args[0].asDouble() ?: throw PyException("TypeError", "$name() requires number")
            PyFloat(fn(d))
        }

        mod.members["sqrt"] = num1("sqrt") { sqrt(it) }
        mod.members["sin"] = num1("sin") { sin(it) }
        mod.members["cos"] = num1("cos") { cos(it) }
        mod.members["tan"] = num1("tan") { tan(it) }
        mod.members["asin"] = num1("asin") { asin(it) }
        mod.members["acos"] = num1("acos") { acos(it) }
        mod.members["atan"] = num1("atan") { atan(it) }
        mod.members["exp"] = num1("exp") { exp(it) }
        mod.members["log10"] = num1("log10") { log10(it) }
        mod.members["log2"] = num1("log2") { log2(it) }
        mod.members["radians"] = num1("radians") { Math.toRadians(it) }
        mod.members["degrees"] = num1("degrees") { Math.toDegrees(it) }
        mod.members["fabs"] = num1("fabs") { abs(it) }
        mod.members["log"] = PyNativeFunction("log") { _, args, _ ->
            val x = args.firstOrNull()?.asDouble() ?: throw PyException("TypeError", "log() requires number")
            if (args.size == 2) {
                val base = args[1].asDouble() ?: throw PyException("TypeError", "log() base must be number")
                PyFloat(ln(x) / ln(base))
            } else PyFloat(ln(x))
        }
        mod.members["floor"] = PyNativeFunction("floor") { _, args, _ ->
            PyBuiltins.requireArgs("floor", args, 1)
            PyInt(floor(args[0].asDouble()!!).toLong())
        }
        mod.members["ceil"] = PyNativeFunction("ceil") { _, args, _ ->
            PyBuiltins.requireArgs("ceil", args, 1)
            PyInt(ceil(args[0].asDouble()!!).toLong())
        }
        mod.members["factorial"] = PyNativeFunction("factorial") { _, args, _ ->
            val n = PyBuiltins.requireInt("factorial", args[0]).toInt()
            if (n < 0) throw PyException("ValueError", "factorial() not defined for negative values")
            var acc = java.math.BigInteger.ONE
            for (i in 2..n) acc = acc.multiply(java.math.BigInteger.valueOf(i.toLong()))
            PyInt(acc)
        }
        mod.members["gcd"] = PyNativeFunction("gcd") { _, args, _ ->
            var res = java.math.BigInteger.ZERO
            for (a in args) {
                val bi = a.asBigInt() ?: throw PyException("TypeError", "gcd requires ints")
                res = res.gcd(bi)
            }
            PyInt(res)
        }
        mod.members["isqrt"] = PyNativeFunction("isqrt") { _, args, _ ->
            val n = PyBuiltins.requireInt("isqrt", args[0])
            if (n < 0) throw PyException("ValueError", "isqrt() argument must be nonnegative")
            PyInt(sqrt(n.toDouble()).toLong())
        }
        mod.members["pow"] = PyNativeFunction("pow") { _, args, _ ->
            PyBuiltins.requireArgs("pow", args, 2)
            PyFloat(args[0].asDouble()!!.pow(args[1].asDouble()!!))
        }
        return mod
    }

    private fun createRandomModule(): PyModule {
        val mod = PyModule("random")
        var rng: Random = Random.Default
        mod.members["seed"] = PyNativeFunction("seed") { _, args, _ ->
            val s = args.firstOrNull()?.pyHash()?.toLong() ?: System.nanoTime()
            rng = Random(s)
            PyNone
        }
        mod.members["random"] = PyNativeFunction("random") { _, _, _ -> PyFloat(rng.nextDouble()) }
        mod.members["uniform"] = PyNativeFunction("uniform") { _, args, _ ->
            val a = args[0].asDouble()!!
            val b = args[1].asDouble()!!
            PyFloat(a + (b - a) * rng.nextDouble())
        }
        mod.members["randint"] = PyNativeFunction("randint") { _, args, _ ->
            val a = PyBuiltins.requireInt("randint", args[0])
            val b = PyBuiltins.requireInt("randint", args[1])
            PyInt(rng.nextLong(a, b + 1))
        }
        mod.members["choice"] = PyNativeFunction("choice") { eval, args, _ ->
            val seq = eval.toIterableList(args[0])
            if (seq.isEmpty()) throw PyException("IndexError", "Cannot choose from an empty sequence")
            seq[rng.nextInt(seq.size)]
        }
        mod.members["shuffle"] = PyNativeFunction("shuffle") { _, args, _ ->
            val list = args[0] as? PyList ?: throw PyException("TypeError", "shuffle() requires list")
            list.elements.shuffle(rng)
            PyNone
        }
        mod.members["sample"] = PyNativeFunction("sample") { eval, args, _ ->
            val seq = eval.toIterableList(args[0]).toMutableList()
            val k = PyBuiltins.requireInt("sample", args[1]).toInt()
            if (k < 0 || k > seq.size) throw PyException("ValueError", "Sample larger than population or is negative")
            seq.shuffle(rng)
            PyList(seq.take(k).toMutableList())
        }
        return mod
    }

    private fun createJsonModule(): PyModule {
        val mod = PyModule("json")
        mod.members["dumps"] = PyNativeFunction("dumps") { _, args, kwargs ->
            PyBuiltins.requireArgs("dumps", args, 1)
            val indent = kwargs["indent"]?.asBigInt()?.toInt()
            PyStr(toJson(args[0], indent, 0))
        }
        mod.members["loads"] = PyNativeFunction("loads") { _, args, _ ->
            val s = (args.firstOrNull() as? PyStr)?.value ?: throw PyException("TypeError", "json.loads expects str")
            JsonParser(s).parseValue()
        }
        mod.members["dump"] = PyNativeFunction("dump") { _, args, kwargs ->
            val fp = args.getOrNull(1) as? PyFile ?: throw PyException("TypeError", "json.dump expects file object")
            val indent = kwargs["indent"]?.asBigInt()?.toInt()
            fp.write(toJson(args[0], indent, 0))
            PyNone
        }
        mod.members["load"] = PyNativeFunction("load") { _, args, _ ->
            val fp = args.firstOrNull() as? PyFile ?: throw PyException("TypeError", "json.load expects file object")
            JsonParser(fp.readAll()).parseValue()
        }
        return mod
    }

    private fun toJson(value: PyValue, indent: Int?, level: Int): String = when (value) {
        is PyNone -> "null"
        is PyBool -> if (value.value) "true" else "false"
        is PyInt -> value.value.toString()
        is PyFloat -> value.value.toString()
        is PyStr -> buildString {
            append('"')
            for (c in value.value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
        is PyList -> formatJsonArray(value.elements, indent, level)
        is PyTuple -> formatJsonArray(value.elements, indent, level)
        is PyDict -> formatJsonObject(value.entries, indent, level)
        is PyFrozenDict -> formatJsonObject(value.entries, indent, level)
        else -> throw PyException("TypeError", "Object of type ${value.typeName} is not JSON serializable")
    }

    private fun formatJsonArray(items: List<PyValue>, indent: Int?, level: Int): String {
        if (items.isEmpty()) return "[]"
        if (indent == null) return items.joinToString(", ", "[", "]") { toJson(it, null, 0) }
        val pad = " ".repeat((level + 1) * indent)
        val closePad = " ".repeat(level * indent)
        return items.joinToString(",\n", "[\n", "\n$closePad]") { "$pad${toJson(it, indent, level + 1)}" }
    }

    private fun formatJsonObject(entries: Map<PyKey, PyValue>, indent: Int?, level: Int): String {
        if (entries.isEmpty()) return "{}"
        if (indent == null) {
            return entries.entries.joinToString(", ", "{", "}") { (k, v) ->
                "${toJson(PyStr(k.value.toDisplayString()), null, 0)}: ${toJson(v, null, 0)}"
            }
        }
        val pad = " ".repeat((level + 1) * indent)
        val closePad = " ".repeat(level * indent)
        return entries.entries.joinToString(",\n", "{\n", "\n$closePad}") { (k, v) ->
            "$pad${toJson(PyStr(k.value.toDisplayString()), indent, level + 1)}: ${toJson(v, indent, level + 1)}"
        }
    }

    private class JsonParser(private val s: String) {
        private var i = 0
        fun parseValue(): PyValue {
            skipWs()
            if (i >= s.length) throw PyException("ValueError", "Invalid JSON: unexpected end")
            return when (s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> PyStr(parseStr())
                't' -> { expect("true"); PyBool.TRUE }
                'f' -> { expect("false"); PyBool.FALSE }
                'n' -> { expect("null"); PyNone }
                else -> parseNum()
            }
        }
        private fun parseObj(): PyDict {
            i++ // '{'
            val map = linkedMapOf<PyKey, PyValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return PyDict(map) }
            while (i < s.length) {
                skipWs()
                val key = parseStr()
                skipWs()
                if (i >= s.length || s[i] != ':') throw PyException("ValueError", "Expected ':' in JSON")
                i++
                val v = parseValue()
                map[PyKey(PyStr(key))] = v
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return PyDict(map) }
                throw PyException("ValueError", "Expected ',' or '}' in JSON")
            }
            throw PyException("ValueError", "Unterminated JSON object")
        }
        private fun parseArr(): PyList {
            i++ // '['
            val list = mutableListOf<PyValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return PyList(list) }
            while (i < s.length) {
                list.add(parseValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return PyList(list) }
                throw PyException("ValueError", "Expected ',' or ']' in JSON")
            }
            throw PyException("ValueError", "Unterminated JSON array")
        }
        private fun parseStr(): String {
            if (s[i] != '"') throw PyException("ValueError", "Expected '\"' in JSON")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < s.length) {
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            throw PyException("ValueError", "Unterminated JSON string")
        }
        private fun parseNum(): PyValue {
            val start = i
            while (i < s.length && s[i] in "0123456789+-.eE") i++
            val tok = s.substring(start, i)
            return if (tok.contains('.') || tok.contains('e', true)) {
                PyFloat(tok.toDoubleOrNull() ?: throw PyException("ValueError", "Invalid JSON number '$tok'"))
            } else {
                PyInt(tok.toBigIntegerOrNull() ?: throw PyException("ValueError", "Invalid JSON number '$tok'"))
            }
        }
        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw PyException("ValueError", "Invalid JSON literal")
            i += lit.length
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

    private fun createDatetimeModule(): PyModule {
        val mod = PyModule("datetime")
        val dtClass = PyClass("datetime", emptyList())
        dtClass.attributes["now"] = PyNativeFunction("now") { _, _, _ ->
            val now = LocalDateTime.now()
            makeDatetimeInstance(dtClass, now)
        }
        val dateClass = PyClass("date", emptyList())
        dateClass.attributes["today"] = PyNativeFunction("today") { _, _, _ ->
            val d = LocalDate.now()
            val inst = PyInstance(dateClass)
            inst.fields["year"] = PyInt(d.year)
            inst.fields["month"] = PyInt(d.monthValue)
            inst.fields["day"] = PyInt(d.dayOfMonth)
            inst.fields["isoformat"] = PyNativeFunction("isoformat") { _, _, _ -> PyStr(d.toString()) }
            inst.fields["__str__"] = PyNativeFunction("__str__") { _, _, _ -> PyStr(d.toString()) }
            inst
        }
        mod.members["datetime"] = dtClass
        mod.members["date"] = dateClass
        return mod
    }

    private fun makeDatetimeInstance(cls: PyClass, dt: LocalDateTime): PyInstance {
        val inst = PyInstance(cls)
        inst.fields["year"] = PyInt(dt.year)
        inst.fields["month"] = PyInt(dt.monthValue)
        inst.fields["day"] = PyInt(dt.dayOfMonth)
        inst.fields["hour"] = PyInt(dt.hour)
        inst.fields["minute"] = PyInt(dt.minute)
        inst.fields["second"] = PyInt(dt.second)
        inst.fields["isoformat"] = PyNativeFunction("isoformat") { _, _, _ -> PyStr(dt.toString()) }
        inst.fields["strftime"] = PyNativeFunction("strftime") { _, args, _ ->
            val fmt = (args.firstOrNull() as? PyStr)?.value ?: "%Y-%m-%d %H:%M:%S"
            val javaFmt = fmt
                .replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")
                .replace("%H", "HH").replace("%M", "mm").replace("%S", "ss")
            PyStr(dt.format(DateTimeFormatter.ofPattern(javaFmt)))
        }
        inst.fields["__str__"] = PyNativeFunction("__str__") { _, _, _ ->
            PyStr(dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        }
        return inst
    }

    private fun createTimeModule(): PyModule {
        val mod = PyModule("time")
        mod.members["time"] = PyNativeFunction("time") { _, _, _ -> PyFloat(System.currentTimeMillis() / 1000.0) }
        mod.members["perf_counter"] = PyNativeFunction("perf_counter") { _, _, _ -> PyFloat(System.nanoTime() / 1_000_000_000.0) }
        mod.members["monotonic"] = PyNativeFunction("monotonic") { _, _, _ -> PyFloat(System.nanoTime() / 1_000_000_000.0) }
        mod.members["sleep"] = PyNativeFunction("sleep") { _, args, _ ->
            val secs = args.firstOrNull()?.asDouble() ?: 0.0
            val ms = (secs * 1000.0).toLong().coerceIn(0L, 10_000L)
            kotlinx.coroutines.delay(ms)
            PyNone
        }
        return mod
    }

    private fun createReModule(): PyModule {
        val mod = PyModule("re")
        mod.members["findall"] = PyNativeFunction("findall") { _, args, _ ->
            val pat = (args[0] as PyStr).value
            val text = (args[1] as PyStr).value
            val matches = Regex(pat).findAll(text).map { m ->
                if (m.groupValues.size > 2) PyTuple(m.groupValues.drop(1).map { PyStr(it) })
                else if (m.groupValues.size == 2) PyStr(m.groupValues[1])
                else PyStr(m.value)
            }.toList()
            PyList(matches.toMutableList())
        }
        mod.members["sub"] = PyNativeFunction("sub") { _, args, _ ->
            val pat = (args[0] as PyStr).value
            val repl = (args[1] as PyStr).value
            val text = (args[2] as PyStr).value
            PyStr(Regex(pat).replace(text, repl))
        }
        mod.members["split"] = PyNativeFunction("split") { _, args, _ ->
            val pat = (args[0] as PyStr).value
            val text = (args[1] as PyStr).value
            PyList(Regex(pat).split(text).map { PyStr(it) }.toMutableList())
        }
        mod.members["search"] = PyNativeFunction("search") { _, args, _ ->
            val pat = (args[0] as PyStr).value
            val text = (args[1] as PyStr).value
            val m = Regex(pat).find(text) ?: return@PyNativeFunction PyNone
            makeMatchObj(m)
        }
        mod.members["match"] = PyNativeFunction("match") { _, args, _ ->
            val pat = (args[0] as PyStr).value
            val text = (args[1] as PyStr).value
            val m = Regex(pat).find(text)
            if (m != null && m.range.first == 0) makeMatchObj(m) else PyNone
        }
        return mod
    }

    private fun makeMatchObj(m: MatchResult): PyInstance {
        val cls = PyClass("Match", emptyList())
        val inst = PyInstance(cls)
        inst.fields["group"] = PyNativeFunction("group") { _, args, _ ->
            val idx = args.firstOrNull()?.asBigInt()?.toInt() ?: 0
            PyStr(m.groupValues.getOrElse(idx) { "" })
        }
        inst.fields["groups"] = PyNativeFunction("groups") { _, _, _ ->
            PyTuple(m.groupValues.drop(1).map { PyStr(it) })
        }
        inst.fields["span"] = PyNativeFunction("span") { _, _, _ ->
            PyTuple(listOf(PyInt(m.range.first), PyInt(m.range.last + 1)))
        }
        return inst
    }

    private fun createCollectionsModule(): PyModule {
        val mod = PyModule("collections")
        mod.members["Counter"] = PyNativeFunction("Counter") { eval, args, _ ->
            val dict = PyDict()
            if (args.isNotEmpty()) {
                for (item in eval.toIterableList(args[0])) {
                    val cur = (dict.get(item)?.asBigInt()?.toLong()) ?: 0L
                    dict.set(item, PyInt(cur + 1))
                }
            }
            dict
        }
        mod.members["deque"] = PyNativeFunction("deque") { eval, args, _ ->
            if (args.isEmpty()) PyList(mutableListOf())
            else PyList(eval.toIterableList(args[0]).toMutableList())
        }
        return mod
    }

    private fun createItertoolsModule(): PyModule {
        val mod = PyModule("itertools")
        mod.members["chain"] = PyNativeFunction("chain") { eval, args, _ ->
            val out = mutableListOf<PyValue>()
            for (a in args) out.addAll(eval.toIterableList(a))
            PyList(out)
        }
        mod.members["accumulate"] = PyNativeFunction("accumulate") { eval, args, _ ->
            val items = eval.toIterableList(args[0])
            if (items.isEmpty()) return@PyNativeFunction PyList(mutableListOf())
            val out = mutableListOf(items[0])
            var acc = items[0]
            for (i in 1 until items.size) {
                acc = eval.applyBinaryOp("+", acc, items[i], 0)
                out.add(acc)
            }
            PyList(out)
        }
        mod.members["product"] = PyNativeFunction("product") { eval, args, _ ->
            var result: List<List<PyValue>> = listOf(emptyList())
            for (a in args) {
                val seq = eval.toIterableList(a)
                result = result.flatMap { prefix -> seq.map { elem -> prefix + elem } }
            }
            PyList(result.map { PyTuple(it) }.toMutableList())
        }
        return mod
    }

    private fun createFunctoolsModule(): PyModule {
        val mod = PyModule("functools")
        mod.members["reduce"] = PyNativeFunction("reduce") { eval, args, _ ->
            val fn = args[0]
            val items = eval.toIterableList(args[1])
            var acc = if (args.size >= 3) args[2] else {
                if (items.isEmpty()) throw PyException("TypeError", "reduce() of empty iterable with no initial value")
                items[0]
            }
            val startIdx = if (args.size >= 3) 0 else 1
            for (i in startIdx until items.size) {
                acc = eval.callValue(fn, listOf(acc, items[i]), emptyMap(), 0)
            }
            acc
        }
        mod.members["cache"] = PyNativeFunction("cache") { _, args, _ ->
            val fn = args[0]
            val memo = mutableMapOf<PyKey, PyValue>()
            PyNativeFunction("cached_${fn.toReprString()}") { eval, callArgs, callKwargs ->
                val key = PyKey(PyTuple(callArgs))
                memo[key] ?: eval.callValue(fn, callArgs, callKwargs, 0).also { memo[key] = it }
            }
        }
        mod.members["lru_cache"] = PyNativeFunction("lru_cache") { _, args, _ ->
            if (args.size == 1 && (args[0] is PyFunction || args[0] is PyNativeFunction)) {
                val fn = args[0]
                val memo = mutableMapOf<PyKey, PyValue>()
                PyNativeFunction("lru_cached") { eval, callArgs, callKwargs ->
                    val key = PyKey(PyTuple(callArgs))
                    memo[key] ?: eval.callValue(fn, callArgs, callKwargs, 0).also { memo[key] = it }
                }
            } else {
                PyNativeFunction("lru_cache_decorator") { _, dArgs, _ ->
                    val fn = dArgs[0]
                    val memo = mutableMapOf<PyKey, PyValue>()
                    PyNativeFunction("lru_cached") { eval, callArgs, callKwargs ->
                        val key = PyKey(PyTuple(callArgs))
                        memo[key] ?: eval.callValue(fn, callArgs, callKwargs, 0).also { memo[key] = it }
                    }
                }
            }
        }
        return mod
    }

    private fun createStatisticsModule(): PyModule {
        val mod = PyModule("statistics")
        mod.members["mean"] = PyNativeFunction("mean") { eval, args, _ ->
            val nums = eval.toIterableList(args[0]).map { it.asDouble()!! }
            PyFloat(nums.average())
        }
        mod.members["median"] = PyNativeFunction("median") { eval, args, _ ->
            val nums = eval.toIterableList(args[0]).map { it.asDouble()!! }.sorted()
            val mid = nums.size / 2
            val res = if (nums.size % 2 == 1) nums[mid] else (nums[mid - 1] + nums[mid]) / 2.0
            PyFloat(res)
        }
        mod.members["stdev"] = PyNativeFunction("stdev") { eval, args, _ ->
            val nums = eval.toIterableList(args[0]).map { it.asDouble()!! }
            val avg = nums.average()
            val variance = nums.sumOf { (it - avg).pow(2) } / (nums.size - 1).coerceAtLeast(1)
            PyFloat(sqrt(variance))
        }
        return mod
    }

    private fun createStringModule(): PyModule {
        val mod = PyModule("string")
        mod.members["ascii_lowercase"] = PyStr("abcdefghijklmnopqrstuvwxyz")
        mod.members["ascii_uppercase"] = PyStr("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        mod.members["ascii_letters"] = PyStr("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        mod.members["digits"] = PyStr("0123456789")
        mod.members["hexdigits"] = PyStr("0123456789abcdefABCDEF")
        mod.members["punctuation"] = PyStr("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
        return mod
    }

    private fun createHashlibModule(): PyModule {
        val mod = PyModule("hashlib")
        fun makeHashFn(algo: String, pyName: String) = PyNativeFunction(pyName) { _, args, _ ->
            val input = (args.firstOrNull() as? PyStr)?.value ?: ""
            val digest = MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            val cls = PyClass("HASH", emptyList())
            val inst = PyInstance(cls)
            inst.fields["hexdigest"] = PyNativeFunction("hexdigest") { _, _, _ -> PyStr(hex) }
            inst.fields["name"] = PyStr(pyName)
            inst
        }
        mod.members["sha256"] = makeHashFn("SHA-256", "sha256")
        mod.members["sha1"] = makeHashFn("SHA-1", "sha1")
        mod.members["md5"] = makeHashFn("MD5", "md5")
        return mod
    }

    private fun createBase64Module(): PyModule {
        val mod = PyModule("base64")
        mod.members["b64encode"] = PyNativeFunction("b64encode") { _, args, _ ->
            val text = args[0].toDisplayString()
            PyStr(Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        }
        mod.members["b64decode"] = PyNativeFunction("b64decode") { _, args, _ ->
            val encoded = args[0].toDisplayString()
            PyStr(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        }
        return mod
    }

    private fun createPathlibModule(workspaceDir: File): PyModule {
        val mod = PyModule("pathlib")
        val pathCls = PyClass("Path", emptyList())
        mod.members["Path"] = PyNativeFunction("Path") { _, args, _ ->
            val rel = args.firstOrNull()?.toDisplayString() ?: "."
            val f = PyBuiltins.resolveSandboxedFile(workspaceDir, rel)
            val inst = PyInstance(pathCls)
            inst.fields["name"] = PyStr(f.name)
            inst.fields["stem"] = PyStr(f.nameWithoutExtension)
            inst.fields["suffix"] = PyStr(if (f.extension.isNotEmpty()) ".${f.extension}" else "")
            inst.fields["exists"] = PyNativeFunction("exists") { _, _, _ -> PyBool.of(f.exists()) }
            inst.fields["read_text"] = PyNativeFunction("read_text") { _, _, _ -> PyStr(f.readText(Charsets.UTF_8)) }
            inst.fields["write_text"] = PyNativeFunction("write_text") { _, wArgs, _ ->
                val txt = wArgs[0].toDisplayString()
                f.parentFile?.mkdirs()
                f.writeText(txt, Charsets.UTF_8)
                PyInt(txt.length)
            }
            inst.fields["__str__"] = PyNativeFunction("__str__") { _, _, _ -> PyStr(rel) }
            inst
        }
        return mod
    }

    /**
     * Python 3.15 PEP 799 Profiling helper module (Tachyon-inspired runtime profiler)
     */
    private fun createProfilingModule(): PyModule {
        val mod = PyModule("profiling")
        mod.members["benchmark"] = PyNativeFunction("benchmark") { eval, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "benchmark() requires a callable")
            val fn = args[0]
            val iterations = (kwargs["iterations"] ?: args.getOrNull(1))?.asBigInt()?.toInt() ?: 10
            val startNs = System.nanoTime()
            repeat(iterations) {
                eval.callValue(fn, emptyList(), emptyMap(), 0)
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val report = PyDict()
            report.set(PyStr("iterations"), PyInt(iterations))
            report.set(PyStr("total_ms"), PyFloat(round(elapsedMs * 1000.0) / 1000.0))
            report.set(PyStr("avg_ms"), PyFloat(round((elapsedMs / iterations.coerceAtLeast(1)) * 1000.0) / 1000.0))
            report.set(PyStr("profiler"), PyStr("Python 3.15 Tachyon Android Sampler"))
            report
        }
        mod.members["sampling"] = mod
        return mod
    }

    /**
     * Python 3.15 PEP 661 sentinels module
     */
    private fun createSentinelsModule(): PyModule {
        val mod = PyModule("sentinels")
        mod.members["Sentinel"] = PyNativeFunction("Sentinel") { _, args, kwargs ->
            val name = (args.firstOrNull() as? PyStr)?.value ?: "SENTINEL"
            val reprText = (kwargs["repr"] as? PyStr)?.value ?: "<$name>"
            PySentinel(name, reprText)
        }
        return mod
    }

    private fun createTypingModule(): PyModule {
        val mod = PyModule("typing")
        listOf("Any", "List", "Dict", "Tuple", "Set", "Optional", "Union", "Callable", "Iterable", "Final").forEach {
            mod.members[it] = PySentinel(it, "typing.$it")
        }
        return mod
    }
}
