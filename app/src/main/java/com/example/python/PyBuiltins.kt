package com.example.python

import java.io.File
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

object PyBuiltins {

    fun install(env: PyEnvironment, workspaceDir: File) {
        // Core types & constructors
        env.defineLocal("None", PyNone)
        env.defineLocal("True", PyBool.TRUE)
        env.defineLocal("False", PyBool.FALSE)
        env.defineLocal("object", PyClass("object", emptyList()))

        // Standard exception classes
        val baseExc = PyClass("BaseException", emptyList())
        val exc = PyClass("Exception", listOf(baseExc))
        env.defineLocal("BaseException", baseExc)
        env.defineLocal("Exception", exc)
        env.defineLocal("KeyboardInterrupt", PyClass("KeyboardInterrupt", listOf(baseExc)))
        env.defineLocal("SystemExit", PyClass("SystemExit", listOf(baseExc)))
        listOf(
            "ValueError", "TypeError", "NameError", "IndexError", "KeyError",
            "ZeroDivisionError", "AttributeError", "RuntimeError", "StopIteration",
            "StopAsyncIteration", "AssertionError", "FileNotFoundError", "ImportError",
            "ModuleNotFoundError", "SyntaxError", "NotImplementedError", "OverflowError",
            "OSError", "IOError", "TimeoutError", "EOFError", "ConnectionError",
            "ConnectionResetError", "BrokenPipeError"
        ).forEach { name ->
            env.defineLocal(name, PyClass(name, listOf(exc)))
        }

        // print(*args, sep=' ', end='\n')
        env.defineLocal("print", PyNativeFunction("print") { eval, args, kwargs ->
            val sep = (kwargs["sep"] as? PyStr)?.value ?: " "
            val end = (kwargs["end"] as? PyStr)?.value ?: "\n"
            val parts = args.map { eval.pyToString(it) }
            eval.emitStdout(parts.joinToString(sep) + end)
            PyNone
        })

        // input(prompt='')
        env.defineLocal("input", PyNativeFunction("input") { eval, args, _ ->
            val prompt = if (args.isNotEmpty()) eval.pyToString(args[0]) else ""
            val response = eval.requestInput(prompt)
            PyStr(response)
        })

        // len(obj)
        env.defineLocal("len", PyNativeFunction("len") { eval, args, _ ->
            requireArgs("len", args, 1)
            PyInt(eval.pyLen(args[0]))
        })

        // type(obj)
        env.defineLocal("type", PyNativeFunction("type") { _, args, _ ->
            requireArgs("type", args, 1)
            PyStr("<class '${args[0].typeName}'>")
        })

        // isinstance(obj, cls)
        env.defineLocal("isinstance", PyNativeFunction("isinstance") { _, args, _ ->
            requireArgs("isinstance", args, 2)
            PyBool.of(checkInstance(args[0], args[1]))
        })

        // range(stop) / range(start, stop[, step])
        env.defineLocal("range", PyNativeFunction("range") { _, args, _ ->
            when (args.size) {
                1 -> PyRange(0L, requireInt("range", args[0]), 1L)
                2 -> PyRange(requireInt("range", args[0]), requireInt("range", args[1]), 1L)
                3 -> {
                    val step = requireInt("range", args[2])
                    if (step == 0L) throw PyException("ValueError", "range() arg 3 must not be zero")
                    PyRange(requireInt("range", args[0]), requireInt("range", args[1]), step)
                }
                else -> throw PyException("TypeError", "range expected 1 to 3 arguments, got ${args.size}")
            }
        })

        // int(x=0, base=10)
        env.defineLocal("int", PyNativeFunction("int") { _, args, _ ->
            when (args.size) {
                0 -> PyInt(0)
                1 -> when (val v = args[0]) {
                    is PyInt -> v
                    is PyBool -> PyInt(v.asBigInt())
                    is PyFloat -> PyInt(v.value.toLong())
                    is PyStr -> {
                        val s = v.value.trim().replace("_", "")
                        val bi = s.toBigIntegerOrNull()
                            ?: throw PyException("ValueError", "invalid literal for int() with base 10: '${v.value}'")
                        PyInt(bi)
                    }
                    else -> throw PyException("TypeError", "int() argument must be a string or number, not '${v.typeName}'")
                }
                2 -> {
                    val s = (args[0] as? PyStr)?.value?.trim()
                        ?: throw PyException("TypeError", "int() can't convert non-string with explicit base")
                    val base = requireInt("int", args[1]).toInt()
                    val clean = s.removePrefix("0x").removePrefix("0X").removePrefix("0b").removePrefix("0B").removePrefix("0o").removePrefix("0O")
                    val bi = clean.toBigIntegerOrNull(base)
                        ?: throw PyException("ValueError", "invalid literal for int() with base $base: '$s'")
                    PyInt(bi)
                }
                else -> throw PyException("TypeError", "int() takes at most 2 arguments")
            }
        })

        // float(x=0.0)
        env.defineLocal("float", PyNativeFunction("float") { _, args, _ ->
            if (args.isEmpty()) return@PyNativeFunction PyFloat(0.0)
            requireArgs("float", args, 1)
            when (val v = args[0]) {
                is PyFloat -> v
                is PyInt -> PyFloat(v.value.toDouble())
                is PyBool -> PyFloat(v.asDouble())
                is PyStr -> {
                    val s = v.value.trim().lowercase()
                    val d = when (s) {
                        "inf", "+inf", "infinity" -> Double.POSITIVE_INFINITY
                        "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                        "nan" -> Double.NaN
                        else -> s.replace("_", "").toDoubleOrNull()
                            ?: throw PyException("ValueError", "could not convert string to float: '${v.value}'")
                    }
                    PyFloat(d)
                }
                else -> throw PyException("TypeError", "float() argument must be a string or real number")
            }
        })

        // str(x='')
        env.defineLocal("str", PyNativeFunction("str") { eval, args, _ ->
            if (args.isEmpty()) PyStr("") else PyStr(eval.pyToString(args[0]))
        })

        // bool(x=False)
        env.defineLocal("bool", PyNativeFunction("bool") { _, args, _ ->
            if (args.isEmpty()) PyBool.FALSE else PyBool.of(args[0].toBool())
        })

        // bytes(source=b'', encoding='utf-8')
        val bytesConstructor = PyNativeFunction("bytes") { eval, args, _ ->
            if (args.isEmpty()) return@PyNativeFunction PyBytes(ByteArray(0))
            when (val first = args[0]) {
                is PyBytes -> PyBytes(first.data.copyOf())
                is PyMemoryView -> PyBytes(first.toByteArray())
                is PyInt -> {
                    val n = first.value.toInt()
                    if (n < 0) throw PyException("ValueError", "negative count")
                    PyBytes(ByteArray(n))
                }
                is PyStr -> {
                    val enc = (args.getOrNull(1) as? PyStr)?.value?.lowercase() ?: "utf-8"
                    val cs = if (enc == "latin-1" || enc == "latin1") Charsets.ISO_8859_1 else Charsets.UTF_8
                    PyBytes(first.value.toByteArray(cs))
                }
                else -> {
                    val items = eval.toIterableList(first)
                    val out = ByteArray(items.size) { idx ->
                        val v = items[idx].asBigInt()?.toInt()
                            ?: throw PyException("TypeError", "'${items[idx].typeName}' object cannot be interpreted as an integer")
                        if (v !in 0..255) throw PyException("ValueError", "bytes must be in range(0, 256)")
                        v.toByte()
                    }
                    PyBytes(out)
                }
            }
        }
        env.defineLocal("bytes", bytesConstructor)
        env.defineLocal("bytearray", bytesConstructor)

        // memoryview(obj)
        env.defineLocal("memoryview", PyNativeFunction("memoryview") { _, args, _ ->
            requireArgs("memoryview", args, 1)
            when (val v = args[0]) {
                is PyBytes -> PyMemoryView(v.data)
                is PyMemoryView -> v
                else -> throw PyException("TypeError", "memoryview: a bytes-like object is required, not '${v.typeName}'")
            }
        })

        // list(iterable=())
        env.defineLocal("list", PyNativeFunction("list") { eval, args, _ ->
            if (args.isEmpty()) PyList(mutableListOf())
            else PyList(eval.toIterableList(args[0]).toMutableList())
        })

        // tuple(iterable=())
        env.defineLocal("tuple", PyNativeFunction("tuple") { eval, args, _ ->
            if (args.isEmpty()) PyTuple(emptyList())
            else PyTuple(eval.toIterableList(args[0]))
        })

        // set(iterable=())
        env.defineLocal("set", PyNativeFunction("set") { eval, args, _ ->
            val out = linkedSetOf<PyKey>()
            if (args.isNotEmpty()) {
                for (item in eval.toIterableList(args[0])) {
                    ensureHashable(item)
                    out.add(PyKey(item))
                }
            }
            PySet(out)
        })

        // dict(...)
        env.defineLocal("dict", PyNativeFunction("dict") { eval, args, kwargs ->
            val map = buildMappingEntries(eval, args, kwargs)
            PyDict(map)
        })

        // Python 3.15 PEP 814: frozendict(...)
        env.defineLocal("frozendict", PyNativeFunction("frozendict") { eval, args, kwargs ->
            val map = buildMappingEntries(eval, args, kwargs)
            PyFrozenDict(map)
        })

        // Python 3.15 PEP 661: sentinel(name, repr=None)
        env.defineLocal("sentinel", PyNativeFunction("sentinel") { _, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "sentinel() missing required argument: 'name'")
            val name = (args[0] as? PyStr)?.value
                ?: throw PyException("TypeError", "sentinel name must be str")
            val reprText = (kwargs["repr"] as? PyStr)?.value
                ?: (args.getOrNull(1) as? PyStr)?.value
                ?: "<$name>"
            PySentinel(name, reprText)
        })

        // abs, round, pow, divmod
        env.defineLocal("abs", PyNativeFunction("abs") { _, args, _ ->
            requireArgs("abs", args, 1)
            when (val v = args[0]) {
                is PyInt -> PyInt(v.value.abs())
                is PyFloat -> PyFloat(abs(v.value))
                is PyBool -> PyInt(v.asBigInt())
                else -> throw PyException("TypeError", "bad operand type for abs(): '${v.typeName}'")
            }
        })

        env.defineLocal("round", PyNativeFunction("round") { _, args, _ ->
            if (args.isEmpty() || args.size > 2) throw PyException("TypeError", "round() takes 1 or 2 arguments")
            val num = args[0].asDouble() ?: throw PyException("TypeError", "type ${args[0].typeName} doesn't define __round__")
            if (args.size == 1) {
                PyInt(round(num).toLong())
            } else {
                val digits = requireInt("round", args[1]).toInt()
                val factor = 10.0.pow(digits)
                PyFloat(round(num * factor) / factor)
            }
        })

        env.defineLocal("pow", PyNativeFunction("pow") { _, args, _ ->
            if (args.size !in 2..3) throw PyException("TypeError", "pow() takes 2 or 3 arguments")
            if (args.size == 3) {
                val a = args[0].asBigInt() ?: throw PyException("TypeError", "3-arg pow requires ints")
                val b = args[1].asBigInt() ?: throw PyException("TypeError", "3-arg pow requires ints")
                val m = args[2].asBigInt() ?: throw PyException("TypeError", "3-arg pow requires ints")
                PyInt(a.modPow(b, m))
            } else {
                val a = args[0]
                val b = args[1]
                if (a is PyInt && b is PyInt && b.value >= BigInteger.ZERO) {
                    PyInt(a.value.pow(b.value.toInt()))
                } else {
                    val da = a.asDouble() ?: throw PyException("TypeError", "unsupported operand type(s) for pow()")
                    val db = b.asDouble() ?: throw PyException("TypeError", "unsupported operand type(s) for pow()")
                    PyFloat(da.pow(db))
                }
            }
        })

        env.defineLocal("divmod", PyNativeFunction("divmod") { _, args, _ ->
            requireArgs("divmod", args, 2)
            val a = args[0].asBigInt()
            val b = args[1].asBigInt()
            if (a != null && b != null) {
                if (b == BigInteger.ZERO) throw PyException("ZeroDivisionError", "integer division or modulo by zero")
                val (q, r) = floorDivMod(a, b)
                PyTuple(listOf(PyInt(q), PyInt(r)))
            } else {
                val da = args[0].asDouble() ?: throw PyException("TypeError", "unsupported operand type(s) for divmod()")
                val db = args[1].asDouble() ?: throw PyException("TypeError", "unsupported operand type(s) for divmod()")
                if (db == 0.0) throw PyException("ZeroDivisionError", "float divmod()")
                val q = kotlin.math.floor(da / db)
                PyTuple(listOf(PyFloat(q), PyFloat(da - q * db)))
            }
        })

        // sum, min, max, sorted, reversed, enumerate, zip, map, filter, any, all
        env.defineLocal("sum", PyNativeFunction("sum") { eval, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "sum() missing iterable")
            var acc: PyValue = kwargs["start"] ?: args.getOrNull(1) ?: PyInt(0)
            for (item in eval.toIterableList(args[0])) {
                acc = eval.applyBinaryOp("+", acc, item, 0)
            }
            acc
        })

        env.defineLocal("min", PyNativeFunction("min") { eval, args, kwargs ->
            val items = if (args.size == 1) eval.toIterableList(args[0]) else args
            if (items.isEmpty()) {
                return@PyNativeFunction kwargs["default"]
                    ?: throw PyException("ValueError", "min() iterable argument is empty")
            }
            val keyFn = kwargs["key"]
            var best = items[0]
            var bestKey = if (keyFn != null) eval.callValue(keyFn, listOf(best), emptyMap(), 0) else best
            for (i in 1 until items.size) {
                val cand = items[i]
                val candKey = if (keyFn != null) eval.callValue(keyFn, listOf(cand), emptyMap(), 0) else cand
                if (eval.compareValues(candKey, bestKey) < 0) {
                    best = cand
                    bestKey = candKey
                }
            }
            best
        })

        env.defineLocal("max", PyNativeFunction("max") { eval, args, kwargs ->
            val items = if (args.size == 1) eval.toIterableList(args[0]) else args
            if (items.isEmpty()) {
                return@PyNativeFunction kwargs["default"]
                    ?: throw PyException("ValueError", "max() iterable argument is empty")
            }
            val keyFn = kwargs["key"]
            var best = items[0]
            var bestKey = if (keyFn != null) eval.callValue(keyFn, listOf(best), emptyMap(), 0) else best
            for (i in 1 until items.size) {
                val cand = items[i]
                val candKey = if (keyFn != null) eval.callValue(keyFn, listOf(cand), emptyMap(), 0) else cand
                if (eval.compareValues(candKey, bestKey) > 0) {
                    best = cand
                    bestKey = candKey
                }
            }
            best
        })

        env.defineLocal("sorted", PyNativeFunction("sorted") { eval, args, kwargs ->
            requireArgs("sorted", args, 1)
            val items = eval.toIterableList(args[0])
            val keyFn = kwargs["key"]
            val reverse = kwargs["reverse"]?.toBool() ?: false
            val keyed = items.map { item ->
                val k = if (keyFn != null) eval.callValue(keyFn, listOf(item), emptyMap(), 0) else item
                item to k
            }
            val sorted = keyed.sortedWith { a, b ->
                val cmp = eval.compareValues(a.second, b.second)
                if (reverse) -cmp else cmp
            }.map { it.first }
            PyList(sorted.toMutableList())
        })

        env.defineLocal("reversed", PyNativeFunction("reversed") { eval, args, _ ->
            requireArgs("reversed", args, 1)
            PyList(eval.toIterableList(args[0]).reversed().toMutableList())
        })

        env.defineLocal("enumerate", PyNativeFunction("enumerate") { eval, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "enumerate() missing iterable")
            val start = (kwargs["start"] ?: args.getOrNull(1))?.let { requireInt("enumerate", it) } ?: 0L
            val items = eval.toIterableList(args[0]).mapIndexed { idx, v ->
                PyTuple(listOf(PyInt(start + idx), v))
            }
            PyList(items.toMutableList())
        })

        env.defineLocal("zip", PyNativeFunction("zip") { eval, args, _ ->
            if (args.isEmpty()) return@PyNativeFunction PyList(mutableListOf())
            val lists = args.map { eval.toIterableList(it) }
            val minLen = lists.minOf { it.size }
            val zipped = (0 until minLen).map { i ->
                PyTuple(lists.map { it[i] })
            }
            PyList(zipped.toMutableList())
        })

        env.defineLocal("map", PyNativeFunction("map") { eval, args, _ ->
            if (args.size < 2) throw PyException("TypeError", "map() must have at least two arguments")
            val fn = args[0]
            val lists = args.drop(1).map { eval.toIterableList(it) }
            val minLen = lists.minOf { it.size }
            val out = mutableListOf<PyValue>()
            for (i in 0 until minLen) {
                out.add(eval.callValue(fn, lists.map { it[i] }, emptyMap(), 0))
            }
            PyList(out)
        })

        env.defineLocal("filter", PyNativeFunction("filter") { eval, args, _ ->
            requireArgs("filter", args, 2)
            val fn = args[0]
            val items = eval.toIterableList(args[1])
            val out = mutableListOf<PyValue>()
            for (item in items) {
                val keep = if (fn is PyNone) item.toBool()
                else eval.callValue(fn, listOf(item), emptyMap(), 0).toBool()
                if (keep) out.add(item)
            }
            PyList(out)
        })

        env.defineLocal("any", PyNativeFunction("any") { eval, args, _ ->
            requireArgs("any", args, 1)
            PyBool.of(eval.toIterableList(args[0]).any { it.toBool() })
        })

        env.defineLocal("all", PyNativeFunction("all") { eval, args, _ ->
            requireArgs("all", args, 1)
            PyBool.of(eval.toIterableList(args[0]).all { it.toBool() })
        })

        // chr, ord, hex, bin, oct, repr, format, id, hash, dir, callable, iter, next
        env.defineLocal("chr", PyNativeFunction("chr") { _, args, _ ->
            requireArgs("chr", args, 1)
            val code = requireInt("chr", args[0]).toInt()
            PyStr(String(Character.toChars(code)))
        })

        env.defineLocal("ord", PyNativeFunction("ord") { _, args, _ ->
            requireArgs("ord", args, 1)
            val s = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "ord() expected string of length 1")
            if (s.isEmpty()) throw PyException("TypeError", "ord() expected a character, but string of length 0 found")
            PyInt(s.codePointAt(0))
        })

        env.defineLocal("hex", PyNativeFunction("hex") { _, args, _ ->
            requireArgs("hex", args, 1)
            val n = args[0].asBigInt() ?: throw PyException("TypeError", "hex() requires int")
            PyStr(if (n < BigInteger.ZERO) "-0x${n.abs().toString(16)}" else "0x${n.toString(16)}")
        })

        env.defineLocal("bin", PyNativeFunction("bin") { _, args, _ ->
            requireArgs("bin", args, 1)
            val n = args[0].asBigInt() ?: throw PyException("TypeError", "bin() requires int")
            PyStr(if (n < BigInteger.ZERO) "-0b${n.abs().toString(2)}" else "0b${n.toString(2)}")
        })

        env.defineLocal("oct", PyNativeFunction("oct") { _, args, _ ->
            requireArgs("oct", args, 1)
            val n = args[0].asBigInt() ?: throw PyException("TypeError", "oct() requires int")
            PyStr(if (n < BigInteger.ZERO) "-0o${n.abs().toString(8)}" else "0o${n.toString(8)}")
        })

        env.defineLocal("repr", PyNativeFunction("repr") { eval, args, _ ->
            requireArgs("repr", args, 1)
            PyStr(eval.pyToRepr(args[0]))
        })

        env.defineLocal("format", PyNativeFunction("format") { eval, args, _ ->
            if (args.isEmpty()) throw PyException("TypeError", "format() takes at least 1 argument")
            val spec = (args.getOrNull(1) as? PyStr)?.value ?: ""
            PyStr(eval.formatValue(args[0], spec))
        })

        env.defineLocal("id", PyNativeFunction("id") { _, args, _ ->
            requireArgs("id", args, 1)
            PyInt(System.identityHashCode(args[0]))
        })

        env.defineLocal("hash", PyNativeFunction("hash") { _, args, _ ->
            requireArgs("hash", args, 1)
            ensureHashable(args[0])
            PyInt(args[0].pyHash())
        })

        env.defineLocal("callable", PyNativeFunction("callable") { _, args, _ ->
            requireArgs("callable", args, 1)
            val v = args[0]
            val isCall = v is PyFunction || v is PyNativeFunction || v is PyClass || v is PyBoundMethod ||
                (v is PyInstance && v.pyClass.lookupAttribute("__call__") != null)
            PyBool.of(isCall)
        })

        env.defineLocal("dir", PyNativeFunction("dir") { eval, args, _ ->
            val names = if (args.isEmpty()) {
                eval.globalEnv.values.keys.sorted()
            } else {
                when (val obj = args[0]) {
                    is PyModule -> obj.members.keys.sorted()
                    is PyLazyModule -> obj.resolve().members.keys.sorted()
                    is PyInstance -> (obj.fields.keys + obj.pyClass.attributes.keys).sorted()
                    is PyClass -> obj.attributes.keys.sorted()
                    else -> listOf("__class__", "__repr__", "__str__")
                }
            }
            PyList(names.map { PyStr(it) }.toMutableList())
        })

        env.defineLocal("getattr", PyNativeFunction("getattr") { eval, args, _ ->
            if (args.size !in 2..3) throw PyException("TypeError", "getattr expected 2 or 3 arguments")
            val name = (args[1] as? PyStr)?.value ?: throw PyException("TypeError", "attribute name must be string")
            try {
                eval.getAttribute(args[0], name, 0)
            } catch (e: PyException) {
                if (e.typeName == "AttributeError" && args.size == 3) args[2] else throw e
            }
        })

        env.defineLocal("setattr", PyNativeFunction("setattr") { eval, args, _ ->
            requireArgs("setattr", args, 3)
            val name = (args[1] as? PyStr)?.value ?: throw PyException("TypeError", "attribute name must be string")
            eval.setAttribute(args[0], name, args[2], 0)
            PyNone
        })

        env.defineLocal("hasattr", PyNativeFunction("hasattr") { eval, args, _ ->
            requireArgs("hasattr", args, 2)
            val name = (args[1] as? PyStr)?.value ?: throw PyException("TypeError", "attribute name must be string")
            try {
                eval.getAttribute(args[0], name, 0)
                PyBool.TRUE
            } catch (e: PyException) {
                if (e.typeName == "AttributeError") PyBool.FALSE else throw e
            }
        })

        env.defineLocal("iter", PyNativeFunction("iter") { eval, args, _ ->
            requireArgs("iter", args, 1)
            if (args[0] is PyIterator) args[0] else PyIterator(eval.toIterableList(args[0]))
        })

        env.defineLocal("next", PyNativeFunction("next") { _, args, _ ->
            if (args.isEmpty()) throw PyException("TypeError", "next expected at least 1 argument")
            val it = args[0] as? PyIterator
                ?: throw PyException("TypeError", "'${args[0].typeName}' object is not an iterator")
            if (it.hasNext()) it.next()
            else if (args.size >= 2) args[1]
            else throw PyException("StopIteration", "")
        })

        // Decorators: property, staticmethod, classmethod
        env.defineLocal("property", PyNativeFunction("property") { _, args, _ ->
            if (args.isEmpty()) throw PyException("TypeError", "property() requires getter")
            PyProperty(args[0], args.getOrNull(1))
        })
        env.defineLocal("staticmethod", PyNativeFunction("staticmethod") { _, args, _ ->
            requireArgs("staticmethod", args, 1)
            PyStaticMethod(args[0])
        })
        env.defineLocal("classmethod", PyNativeFunction("classmethod") { _, args, _ ->
            requireArgs("classmethod", args, 1)
            PyClassMethod(args[0])
        })

        // open(file, mode='r', encoding='utf-8') -> Sandboxed inside workspaceDir, PEP 686 UTF-8 default!
        env.defineLocal("open", PyNativeFunction("open") { _, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "open() missing required argument: 'file'")
            val pathStr = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "file path must be str")
            val mode = (kwargs["mode"] as? PyStr)?.value ?: (args.getOrNull(1) as? PyStr)?.value ?: "r"
            val encoding = (kwargs["encoding"] as? PyStr)?.value ?: "utf-8"

            val resolved = resolveSandboxedFile(workspaceDir, pathStr)
            if (mode.contains('w')) {
                resolved.parentFile?.mkdirs()
                resolved.writeText("", Charsets.UTF_8)
            } else if (mode.contains('r') && !resolved.exists()) {
                throw PyException("FileNotFoundError", "[Errno 2] No such file or directory: '$pathStr'")
            }
            PyFile(resolved, mode, encoding)
        })

        // eval(expr_str) & exec(code_str)
        env.defineLocal("eval", PyNativeFunction("eval") { eval, args, _ ->
            requireArgs("eval", args, 1)
            val code = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "eval() arg 1 must be a string")
            val tokens = PyLexer(code, "<eval>").tokenize()
            val expr = PyParser(tokens, "<eval>").parseSingleExpression()
            eval.evalExpr(expr, eval.currentEnv)
        })

        env.defineLocal("exec", PyNativeFunction("exec") { eval, args, _ ->
            requireArgs("exec", args, 1)
            val code = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "exec() arg 1 must be a string")
            val tokens = PyLexer(code, "<exec>").tokenize()
            val stmts = PyParser(tokens, "<exec>").parseProgram()
            for (s in stmts) eval.execStmt(s, eval.currentEnv)
            PyNone
        })
    }

    private suspend fun buildMappingEntries(
        eval: PyEvaluator,
        args: List<PyValue>,
        kwargs: Map<String, PyValue>
    ): LinkedHashMap<PyKey, PyValue> {
        val map = linkedMapOf<PyKey, PyValue>()
        if (args.isNotEmpty()) {
            when (val first = args[0]) {
                is PyDict -> map.putAll(first.entries)
                is PyFrozenDict -> map.putAll(first.entries)
                else -> {
                    for (pair in eval.toIterableList(first)) {
                        val items = eval.toIterableList(pair)
                        if (items.size != 2) throw PyException("ValueError", "dictionary update sequence element has length ${items.size}; 2 is required")
                        ensureHashable(items[0])
                        map[PyKey(items[0])] = items[1]
                    }
                }
            }
        }
        for ((k, v) in kwargs) {
            map[PyKey(PyStr(k))] = v
        }
        return map
    }

    fun checkInstance(obj: PyValue, cls: PyValue): Boolean {
        if (cls is PyTuple) return cls.elements.any { checkInstance(obj, it) }
        if (cls is PyClass) {
            if (cls.name == "object") return true
            return obj is PyInstance && obj.pyClass.isSubclassOf(cls)
        }
        if (cls is PyNativeFunction) {
            return when (cls.name) {
                "int" -> obj is PyInt || obj is PyBool
                "bool" -> obj is PyBool
                "float" -> obj is PyFloat
                "str" -> obj is PyStr
                "list" -> obj is PyList
                "tuple" -> obj is PyTuple
                "dict" -> obj is PyDict
                "frozendict" -> obj is PyFrozenDict
                "set" -> obj is PySet
                "range" -> obj is PyRange
                else -> obj.typeName == cls.name
            }
        }
        return false
    }

    fun resolveSandboxedFile(workspaceDir: File, relativePath: String): File {
        workspaceDir.mkdirs()
        val candidate = File(workspaceDir, relativePath).canonicalFile
        val rootCanonical = workspaceDir.canonicalFile
        if (!candidate.path.startsWith(rootCanonical.path)) {
            throw PyException("OSError", "Access outside sandbox workspace is not allowed: '$relativePath'")
        }
        return candidate
    }

    fun floorDivMod(a: BigInteger, b: BigInteger): Pair<BigInteger, BigInteger> {
        val dr = a.divideAndRemainder(b)
        var q = dr[0]
        var r = dr[1]
        if (r != BigInteger.ZERO && (r.signum() != b.signum())) {
            q = q.subtract(BigInteger.ONE)
            r = r.add(b)
        }
        return q to r
    }

    fun requireArgs(name: String, args: List<PyValue>, count: Int) {
        if (args.size != count) {
            throw PyException("TypeError", "$name() takes exactly $count argument(s) (${args.size} given)")
        }
    }

    fun requireInt(name: String, value: PyValue): Long {
        return value.asBigInt()?.toLong()
            ?: throw PyException("TypeError", "$name() expected integer, got '${value.typeName}'")
    }
}
