package com.example.python

object PyMethods {

    fun getBuiltinMethod(obj: PyValue, name: String): PyValue? = when (obj) {
        is PyStr -> getStrMethod(obj, name)
        is PyBytes -> getBytesMethod(obj, name)
        is PyMemoryView -> getMemoryViewMethod(obj, name)
        is PyInt -> getIntMethod(obj, name)
        is PyNativeFunction -> getNativeTypeMethod(obj, name)
        is PyList -> getListMethod(obj, name)
        is PyTuple -> getTupleMethod(obj, name)
        is PyDict -> getDictMethod(obj, name)
        is PyFrozenDict -> getFrozenDictMethod(obj, name)
        is PySet -> getSetMethod(obj, name)
        is PyFile -> getFileMethod(obj, name)
        else -> null
    }

    private fun getStrMethod(s: PyStr, name: String): PyValue? {
        val text = s.value
        return when (name) {
            "upper" -> PyNativeFunction("upper") { _, _, _ -> PyStr(text.uppercase()) }
            "lower" -> PyNativeFunction("lower") { _, _, _ -> PyStr(text.lowercase()) }
            "title" -> PyNativeFunction("title") { _, _, _ ->
                PyStr(text.split(" ").joinToString(" ") { w ->
                    w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                })
            }
            "capitalize" -> PyNativeFunction("capitalize") { _, _, _ ->
                PyStr(text.lowercase().replaceFirstChar { it.uppercase() })
            }
            "strip" -> PyNativeFunction("strip") { _, args, _ ->
                val chars = (args.firstOrNull() as? PyStr)?.value?.toCharArray()
                PyStr(if (chars == null) text.trim() else text.trim(*chars))
            }
            "lstrip" -> PyNativeFunction("lstrip") { _, args, _ ->
                val chars = (args.firstOrNull() as? PyStr)?.value?.toCharArray()
                PyStr(if (chars == null) text.trimStart() else text.trimStart(*chars))
            }
            "rstrip" -> PyNativeFunction("rstrip") { _, args, _ ->
                val chars = (args.firstOrNull() as? PyStr)?.value?.toCharArray()
                PyStr(if (chars == null) text.trimEnd() else text.trimEnd(*chars))
            }
            "split" -> PyNativeFunction("split") { _, args, _ ->
                val sep = (args.firstOrNull() as? PyStr)?.value
                val parts = if (sep == null) {
                    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                } else {
                    val maxSplit = args.getOrNull(1)?.asBigInt()?.toInt()
                    if (maxSplit != null && maxSplit >= 0) text.split(sep, limit = maxSplit + 1)
                    else text.split(sep)
                }
                PyList(parts.map { PyStr(it) }.toMutableList())
            }
            "splitlines" -> PyNativeFunction("splitlines") { _, _, _ ->
                PyList(text.lines().map { PyStr(it) }.toMutableList())
            }
            "join" -> PyNativeFunction("join") { eval, args, _ ->
                PyBuiltins.requireArgs("join", args, 1)
                val items = eval.toIterableList(args[0]).map {
                    (it as? PyStr)?.value ?: throw PyException("TypeError", "sequence item expected str instance, ${it.typeName} found")
                }
                PyStr(items.joinToString(text))
            }
            "replace" -> PyNativeFunction("replace") { _, args, _ ->
                if (args.size < 2) throw PyException("TypeError", "replace() requires at least 2 arguments")
                val old = (args[0] as PyStr).value
                val new = (args[1] as PyStr).value
                PyStr(text.replace(old, new))
            }
            "find" -> PyNativeFunction("find") { _, args, _ ->
                val sub = (args[0] as PyStr).value
                val start = args.getOrNull(1)?.asBigInt()?.toInt() ?: 0
                PyInt(text.indexOf(sub, start))
            }
            "index" -> PyNativeFunction("index") { _, args, _ ->
                val sub = (args[0] as PyStr).value
                val idx = text.indexOf(sub)
                if (idx < 0) throw PyException("ValueError", "substring not found")
                PyInt(idx)
            }
            "count" -> PyNativeFunction("count") { _, args, _ ->
                val sub = (args[0] as PyStr).value
                if (sub.isEmpty()) PyInt(text.length + 1)
                else {
                    var count = 0
                    var i = 0
                    while (true) {
                        val found = text.indexOf(sub, i)
                        if (found < 0) break
                        count++
                        i = found + sub.length
                    }
                    PyInt(count)
                }
            }
            "startswith" -> PyNativeFunction("startswith") { _, args, _ ->
                val p = args[0]
                val ok = if (p is PyTuple) p.elements.any { text.startsWith((it as PyStr).value) }
                else text.startsWith((p as PyStr).value)
                PyBool.of(ok)
            }
            "endswith" -> PyNativeFunction("endswith") { _, args, _ ->
                val p = args[0]
                val ok = if (p is PyTuple) p.elements.any { text.endsWith((it as PyStr).value) }
                else text.endsWith((p as PyStr).value)
                PyBool.of(ok)
            }
            "isdigit" -> PyNativeFunction("isdigit") { _, _, _ -> PyBool.of(text.isNotEmpty() && text.all { it.isDigit() }) }
            "isalpha" -> PyNativeFunction("isalpha") { _, _, _ -> PyBool.of(text.isNotEmpty() && text.all { it.isLetter() }) }
            "isalnum" -> PyNativeFunction("isalnum") { _, _, _ -> PyBool.of(text.isNotEmpty() && text.all { it.isLetterOrDigit() }) }
            "isspace" -> PyNativeFunction("isspace") { _, _, _ -> PyBool.of(text.isNotEmpty() && text.all { it.isWhitespace() }) }
            "zfill" -> PyNativeFunction("zfill") { _, args, _ ->
                val width = PyBuiltins.requireInt("zfill", args[0]).toInt()
                PyStr(text.padStart(width, '0'))
            }
            "center" -> PyNativeFunction("center") { _, args, _ ->
                val width = PyBuiltins.requireInt("center", args[0]).toInt()
                val fill = (args.getOrNull(1) as? PyStr)?.value?.firstOrNull() ?: ' '
                if (text.length >= width) PyStr(text)
                else {
                    val left = (width - text.length) / 2
                    val right = width - text.length - left
                    PyStr(fill.toString().repeat(left) + text + fill.toString().repeat(right))
                }
            }
            "format" -> PyNativeFunction("format") { eval, args, kwargs ->
                var autoIdx = 0
                val regex = Regex("\\{([^}]*)\\}")
                val sb = StringBuilder()
                var lastEnd = 0
                for (m in regex.findAll(text)) {
                    sb.append(text, lastEnd, m.range.first)
                    val inside = m.groupValues[1]
                    val parts = inside.split(":", limit = 2)
                    val key = parts[0]
                    val spec = parts.getOrElse(1) { "" }
                    val v = when {
                        key.isEmpty() -> args.getOrElse(autoIdx++) { PyNone }
                        key.toIntOrNull() != null -> args.getOrElse(key.toInt()) { PyNone }
                        else -> kwargs[key] ?: PyNone
                    }
                    sb.append(eval.formatValue(v, spec))
                    lastEnd = m.range.last + 1
                }
                sb.append(text, lastEnd, text.length)
                PyStr(sb.toString())
            }
            "encode" -> PyNativeFunction("encode") { _, args, kwargs ->
                val enc = ((kwargs["encoding"] ?: args.firstOrNull()) as? PyStr)?.value?.lowercase() ?: "utf-8"
                val charset = when (enc) {
                    "latin-1", "latin1", "iso-8859-1" -> Charsets.ISO_8859_1
                    "ascii" -> Charsets.US_ASCII
                    else -> Charsets.UTF_8
                }
                PyBytes(text.toByteArray(charset))
            }
            else -> null
        }
    }

    private fun getBytesMethod(b: PyBytes, name: String): PyValue? = when (name) {
        "decode" -> PyNativeFunction("decode") { _, args, kwargs ->
            val enc = ((kwargs["encoding"] ?: args.firstOrNull()) as? PyStr)?.value?.lowercase() ?: "utf-8"
            val charset = when (enc) {
                "latin-1", "latin1", "iso-8859-1" -> Charsets.ISO_8859_1
                "ascii" -> Charsets.US_ASCII
                else -> Charsets.UTF_8
            }
            PyStr(String(b.data, charset))
        }
        "hex" -> PyNativeFunction("hex") { _, _, _ ->
            PyStr(b.data.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }
        "startswith" -> PyNativeFunction("startswith") { _, args, _ ->
            val prefix = extractBytes(args[0])
            val ok = b.data.size >= prefix.size && prefix.indices.all { b.data[it] == prefix[it] }
            PyBool.of(ok)
        }
        "endswith" -> PyNativeFunction("endswith") { _, args, _ ->
            val suffix = extractBytes(args[0])
            val off = b.data.size - suffix.size
            val ok = off >= 0 && suffix.indices.all { b.data[off + it] == suffix[it] }
            PyBool.of(ok)
        }
        else -> null
    }

    private fun getMemoryViewMethod(mv: PyMemoryView, name: String): PyValue? = when (name) {
        "tobytes" -> PyNativeFunction("tobytes") { _, _, _ -> PyBytes(mv.toByteArray()) }
        "tolist" -> PyNativeFunction("tolist") { _, _, _ ->
            PyList(mv.toByteArray().map { PyInt(it.toInt() and 0xFF) }.toMutableList())
        }
        "hex" -> PyNativeFunction("hex") { _, _, _ ->
            PyStr(mv.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }
        else -> null
    }

    private fun getIntMethod(num: PyInt, name: String): PyValue? = when (name) {
        "to_bytes" -> PyNativeFunction("to_bytes") { _, args, kwargs ->
            val length = (kwargs["length"] ?: args.getOrNull(0))?.asBigInt()?.toInt() ?: 1
            val byteorder = ((kwargs["byteorder"] ?: args.getOrNull(1)) as? PyStr)?.value ?: "big"
            val signed = (kwargs["signed"] ?: args.getOrNull(2))?.toBool() ?: false
            val raw = num.rawLeBytes
            if (raw != null && !signed && byteorder == "little") {
                if (length < 0) throw PyException("ValueError", "length argument must be non-negative")
                if (raw.size == length) {
                    PyBytes(raw.copyOf())
                } else if (raw.size < length) {
                    val out = ByteArray(length)
                    System.arraycopy(raw, 0, out, 0, raw.size)
                    PyBytes(out)
                } else {
                    for (i in length until raw.size) {
                        if (raw[i].toInt() != 0) throw PyException("OverflowError", "int too big to convert")
                    }
                    PyBytes(raw.copyOf(length))
                }
            } else {
                PyBytes(bigIntToBytes(num.value, length, byteorder, signed))
            }
        }
        "bit_length" -> PyNativeFunction("bit_length") { _, _, _ ->
            PyInt.of(num.value.abs().bitLength())
        }
        else -> null
    }

    private fun getNativeTypeMethod(fn: PyNativeFunction, name: String): PyValue? {
        if (fn.name == "int" && name == "from_bytes") {
            return PyNativeFunction("from_bytes") { _, args, kwargs ->
                val src = args[0]
                val byteorder = ((kwargs["byteorder"] ?: args.getOrNull(1)) as? PyStr)?.value ?: "big"
                val signed = (kwargs["signed"] ?: args.getOrNull(2))?.toBool() ?: false
                if (!signed && byteorder == "little") {
                    when (src) {
                        is PyBytes -> if (src.data.size > 8) return@PyNativeFunction PyInt.fromUnsignedLeBytes(src.data.copyOf())
                        is PyMemoryView -> if (src.length > 8) return@PyNativeFunction PyInt.fromUnsignedLeBytes(src.toByteArray())
                        else -> Unit
                    }
                }
                val raw = extractBytes(src)
                PyInt(bytesToBigInt(raw, byteorder, signed))
            }
        }
        if (fn.name == "bytes" && name == "fromhex") {
            return PyNativeFunction("fromhex") { _, args, _ ->
                val hex = (args[0] as PyStr).value.replace(Regex("\\s+"), "")
                if (hex.length % 2 != 0) throw PyException("ValueError", "non-hexadecimal number found in fromhex()")
                val out = ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                PyBytes(out)
            }
        }
        return null
    }

    fun extractBytes(v: PyValue): ByteArray = when (v) {
        is PyBytes -> v.data
        is PyMemoryView -> v.toByteArray()
        is PyStr -> v.value.toByteArray(Charsets.UTF_8)
        else -> throw PyException("TypeError", "a bytes-like object is required, not '${v.typeName}'")
    }

    fun bytesToBigInt(bytes: ByteArray, byteorder: String, signed: Boolean): java.math.BigInteger {
        if (bytes.isEmpty()) return java.math.BigInteger.ZERO
        val bigEndian = when (byteorder) {
            "big" -> bytes
            "little" -> bytes.reversedArray()
            else -> throw PyException("ValueError", "byteorder must be either 'little' or 'big'")
        }
        return if (signed) java.math.BigInteger(bigEndian) else java.math.BigInteger(1, bigEndian)
    }

    fun bigIntToBytes(value: java.math.BigInteger, length: Int, byteorder: String, signed: Boolean): ByteArray {
        if (length < 0) throw PyException("ValueError", "length argument must be non-negative")
        if (!signed && value.signum() < 0) throw PyException("OverflowError", "can't convert negative int to unsigned")
        if (length == 0) {
            if (value == java.math.BigInteger.ZERO) return ByteArray(0)
            throw PyException("OverflowError", "int too big to convert")
        }
        val raw = value.toByteArray()
        val bigEndian = ByteArray(length)
        if (signed && value.signum() < 0) {
            bigEndian.fill(0xFF.toByte())
        }
        // Copy least-significant bytes from raw into bigEndian
        val srcStart = if (!signed && raw.size > 1 && raw[0] == 0.toByte() && raw.size - 1 <= length) 1 else 0
        val copyLen = raw.size - srcStart
        if (copyLen > length) {
            // Allow leading sign/zero bytes if they fit
            val trimStart = raw.size - length
            val padByte: Byte = if (signed && value.signum() < 0) 0xFF.toByte() else 0x00.toByte()
            for (i in 0 until trimStart) {
                if (raw[i] != padByte) throw PyException("OverflowError", "int too big to convert")
            }
            System.arraycopy(raw, trimStart, bigEndian, 0, length)
        } else {
            System.arraycopy(raw, srcStart, bigEndian, length - copyLen, copyLen)
        }
        return when (byteorder) {
            "big" -> bigEndian
            "little" -> bigEndian.reversedArray()
            else -> throw PyException("ValueError", "byteorder must be either 'little' or 'big'")
        }
    }

    private fun getListMethod(list: PyList, name: String): PyValue? = when (name) {
        "append" -> PyNativeFunction("append") { _, args, _ ->
            PyBuiltins.requireArgs("append", args, 1)
            list.elements.add(args[0])
            PyNone
        }
        "extend" -> PyNativeFunction("extend") { eval, args, _ ->
            PyBuiltins.requireArgs("extend", args, 1)
            list.elements.addAll(eval.toIterableList(args[0]))
            PyNone
        }
        "insert" -> PyNativeFunction("insert") { _, args, _ ->
            PyBuiltins.requireArgs("insert", args, 2)
            var idx = PyBuiltins.requireInt("insert", args[0]).toInt()
            if (idx < 0) idx = (list.elements.size + idx).coerceAtLeast(0)
            idx = idx.coerceAtMost(list.elements.size)
            list.elements.add(idx, args[1])
            PyNone
        }
        "pop" -> PyNativeFunction("pop") { _, args, _ ->
            if (list.elements.isEmpty()) throw PyException("IndexError", "pop from empty list")
            val rawIdx = args.firstOrNull()?.let { PyBuiltins.requireInt("pop", it).toInt() } ?: (list.elements.size - 1)
            val idx = if (rawIdx < 0) list.elements.size + rawIdx else rawIdx
            if (idx !in list.elements.indices) throw PyException("IndexError", "pop index out of range")
            list.elements.removeAt(idx)
        }
        "remove" -> PyNativeFunction("remove") { _, args, _ ->
            PyBuiltins.requireArgs("remove", args, 1)
            val idx = list.elements.indexOfFirst { it.pyEquals(args[0]) }
            if (idx < 0) throw PyException("ValueError", "list.remove(x): x not in list")
            list.elements.removeAt(idx)
            PyNone
        }
        "clear" -> PyNativeFunction("clear") { _, _, _ -> list.elements.clear(); PyNone }
        "copy" -> PyNativeFunction("copy") { _, _, _ -> PyList(list.elements.toMutableList()) }
        "reverse" -> PyNativeFunction("reverse") { _, _, _ -> list.elements.reverse(); PyNone }
        "sort" -> PyNativeFunction("sort") { eval, _, kwargs ->
            val keyFn = kwargs["key"]
            val reverse = kwargs["reverse"]?.toBool() ?: false
            val keyed = list.elements.map { item ->
                val k = if (keyFn != null) eval.callValue(keyFn, listOf(item), emptyMap(), 0) else item
                item to k
            }.sortedWith { a, b ->
                val cmp = eval.compareValues(a.second, b.second)
                if (reverse) -cmp else cmp
            }.map { it.first }
            list.elements.clear()
            list.elements.addAll(keyed)
            PyNone
        }
        "count" -> PyNativeFunction("count") { _, args, _ ->
            PyInt(list.elements.count { it.pyEquals(args[0]) })
        }
        "index" -> PyNativeFunction("index") { _, args, _ ->
            val idx = list.elements.indexOfFirst { it.pyEquals(args[0]) }
            if (idx < 0) throw PyException("ValueError", "${args[0].toReprString()} is not in list")
            PyInt(idx)
        }
        else -> null
    }

    private fun getTupleMethod(tuple: PyTuple, name: String): PyValue? = when (name) {
        "count" -> PyNativeFunction("count") { _, args, _ ->
            PyInt(tuple.elements.count { it.pyEquals(args[0]) })
        }
        "index" -> PyNativeFunction("index") { _, args, _ ->
            val idx = tuple.elements.indexOfFirst { it.pyEquals(args[0]) }
            if (idx < 0) throw PyException("ValueError", "tuple.index(x): x not in tuple")
            PyInt(idx)
        }
        else -> null
    }

    private fun getDictMethod(dict: PyDict, name: String): PyValue? = when (name) {
        "get" -> PyNativeFunction("get") { _, args, _ ->
            val default = args.getOrNull(1) ?: PyNone
            dict.get(args[0]) ?: default
        }
        "keys" -> PyNativeFunction("keys") { _, _, _ ->
            PyList(dict.entries.keys.map { it.value }.toMutableList())
        }
        "values" -> PyNativeFunction("values") { _, _, _ ->
            PyList(dict.entries.values.toMutableList())
        }
        "items" -> PyNativeFunction("items") { _, _, _ ->
            PyList(dict.entries.entries.map { PyTuple(listOf(it.key.value, it.value)) }.toMutableList())
        }
        "pop" -> PyNativeFunction("pop") { _, args, _ ->
            val key = PyKey(args[0])
            val removed = dict.entries.remove(key)
            removed ?: args.getOrNull(1) ?: throw PyException("KeyError", args[0].toReprString())
        }
        "update" -> PyNativeFunction("update") { _, args, kwargs ->
            args.firstOrNull()?.let { arg ->
                when (arg) {
                    is PyDict -> dict.entries.putAll(arg.entries)
                    is PyFrozenDict -> dict.entries.putAll(arg.entries)
                    else -> throw PyException("TypeError", "update() expects a mapping")
                }
            }
            for ((k, v) in kwargs) dict.set(PyStr(k), v)
            PyNone
        }
        "setdefault" -> PyNativeFunction("setdefault") { _, args, _ ->
            val existing = dict.get(args[0])
            if (existing != null) existing
            else {
                val def = args.getOrNull(1) ?: PyNone
                dict.set(args[0], def)
                def
            }
        }
        "clear" -> PyNativeFunction("clear") { _, _, _ -> dict.entries.clear(); PyNone }
        "copy" -> PyNativeFunction("copy") { _, _, _ -> PyDict(LinkedHashMap(dict.entries)) }
        else -> null
    }

    private fun getFrozenDictMethod(dict: PyFrozenDict, name: String): PyValue? = when (name) {
        "get" -> PyNativeFunction("get") { _, args, _ ->
            val default = args.getOrNull(1) ?: PyNone
            dict.get(args[0]) ?: default
        }
        "keys" -> PyNativeFunction("keys") { _, _, _ ->
            PyList(dict.entries.keys.map { it.value }.toMutableList())
        }
        "values" -> PyNativeFunction("values") { _, _, _ ->
            PyList(dict.entries.values.toMutableList())
        }
        "items" -> PyNativeFunction("items") { _, _, _ ->
            PyList(dict.entries.entries.map { PyTuple(listOf(it.key.value, it.value)) }.toMutableList())
        }
        "copy" -> PyNativeFunction("copy") { _, _, _ -> dict }
        else -> null
    }

    private fun getSetMethod(set: PySet, name: String): PyValue? = when (name) {
        "add" -> PyNativeFunction("add") { _, args, _ ->
            ensureHashable(args[0])
            set.elements.add(PyKey(args[0]))
            PyNone
        }
        "remove" -> PyNativeFunction("remove") { _, args, _ ->
            if (!set.elements.remove(PyKey(args[0]))) throw PyException("KeyError", args[0].toReprString())
            PyNone
        }
        "discard" -> PyNativeFunction("discard") { _, args, _ ->
            set.elements.remove(PyKey(args[0]))
            PyNone
        }
        "clear" -> PyNativeFunction("clear") { _, _, _ -> set.elements.clear(); PyNone }
        "union" -> PyNativeFunction("union") { eval, args, _ ->
            val out = LinkedHashSet(set.elements)
            for (a in args) {
                for (item in eval.toIterableList(a)) {
                    ensureHashable(item)
                    out.add(PyKey(item))
                }
            }
            PySet(out)
        }
        "intersection" -> PyNativeFunction("intersection") { eval, args, _ ->
            val other = eval.toIterableList(args[0]).map { PyKey(it) }.toSet()
            PySet(LinkedHashSet(set.elements.filter { it in other }))
        }
        "difference" -> PyNativeFunction("difference") { eval, args, _ ->
            val other = eval.toIterableList(args[0]).map { PyKey(it) }.toSet()
            PySet(LinkedHashSet(set.elements.filter { it !in other }))
        }
        else -> null
    }

    private fun getFileMethod(file: PyFile, name: String): PyValue? = when (name) {
        "read" -> PyNativeFunction("read") { _, _, _ -> PyStr(file.readAll()) }
        "readline" -> PyNativeFunction("readline") { _, _, _ -> PyStr(file.readLine()) }
        "readlines" -> PyNativeFunction("readlines") { _, _, _ ->
            PyList(file.readLines().map { PyStr(it) }.toMutableList())
        }
        "write" -> PyNativeFunction("write") { _, args, _ ->
            val txt = (args.firstOrNull() as? PyStr)?.value ?: throw PyException("TypeError", "write() argument must be str")
            PyInt(file.write(txt))
        }
        "close" -> PyNativeFunction("close") { _, _, _ -> file.close(); PyNone }
        "__enter__" -> PyNativeFunction("__enter__") { _, _, _ -> file }
        "__exit__" -> PyNativeFunction("__exit__") { _, _, _ -> file.close(); PyNone }
        else -> null
    }
}
