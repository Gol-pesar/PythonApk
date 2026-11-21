package com.example.python

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object PyNetworkStdLib {

    // -------------------------------------------------------------------------
    // struct module
    // -------------------------------------------------------------------------
    fun createStructModule(): PyModule {
        val mod = PyModule("struct")
        mod.members["pack"] = PyNativeFunction("pack") { _, args, _ ->
            if (args.isEmpty()) throw PyException("TypeError", "pack expected at least 1 argument")
            val fmt = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "pack() format must be str")
            PyBytes(structPack(fmt, args.drop(1)))
        }
        mod.members["unpack"] = PyNativeFunction("unpack") { _, args, _ ->
            PyBuiltins.requireArgs("unpack", args, 2)
            val fmt = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "unpack() format must be str")
            val data = PyMethods.extractBytes(args[1])
            PyTuple(structUnpack(fmt, data))
        }
        mod.members["calcsize"] = PyNativeFunction("calcsize") { _, args, _ ->
            PyBuiltins.requireArgs("calcsize", args, 1)
            val fmt = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "calcsize() format must be str")
            PyInt(structCalcSize(fmt))
        }
        return mod
    }

    private fun parseStructSpec(fmt: String): Pair<ByteOrder, List<Pair<Int, Char>>> {
        var i = 0
        val order = when (fmt.firstOrNull()) {
            '!', '>' -> { i = 1; ByteOrder.BIG_ENDIAN }
            '<' -> { i = 1; ByteOrder.LITTLE_ENDIAN }
            '@', '=' -> { i = 1; ByteOrder.nativeOrder() }
            else -> ByteOrder.nativeOrder()
        }
        val items = mutableListOf<Pair<Int, Char>>()
        while (i < fmt.length) {
            if (fmt[i].isWhitespace()) { i++; continue }
            var count = 0
            var hasCount = false
            while (i < fmt.length && fmt[i].isDigit()) {
                count = count * 10 + (fmt[i] - '0')
                hasCount = true
                i++
            }
            if (i >= fmt.length) break
            val code = fmt[i++]
            items.add((if (hasCount) count else 1) to code)
        }
        return order to items
    }

    private fun structCalcSize(fmt: String): Int {
        val (_, items) = parseStructSpec(fmt)
        return items.sumOf { (count, code) ->
            when (code) {
                'x', 'b', 'B', '?', 's', 'c' -> count
                'h', 'H' -> count * 2
                'i', 'I', 'l', 'L', 'f' -> count * 4
                'q', 'Q', 'd' -> count * 8
                else -> throw PyException("ValueError", "bad char in struct format: '$code'")
            }
        }
    }

    private fun structPack(fmt: String, values: List<PyValue>): ByteArray {
        val (order, items) = parseStructSpec(fmt)
        val out = ByteArrayOutputStream()
        var idx = 0
        fun nextVal(): PyValue = values.getOrNull(idx++)
            ?: throw PyException("ValueError", "struct.pack: not enough values for format '$fmt'")

        for ((count, code) in items) {
            when (code) {
                'x' -> repeat(count) { out.write(0) }
                's' -> {
                    val b = PyMethods.extractBytes(nextVal())
                    for (j in 0 until count) out.write(if (j < b.size) b[j].toInt() and 0xFF else 0)
                }
                else -> repeat(count) {
                    val v = nextVal()
                    val buf = when (code) {
                        'b' -> ByteBuffer.allocate(1).order(order).put(v.asBigInt()!!.toByte())
                        'B' -> ByteBuffer.allocate(1).order(order).put((v.asBigInt()!!.toInt() and 0xFF).toByte())
                        '?' -> ByteBuffer.allocate(1).order(order).put(if (v.toBool()) 1 else 0)
                        'h' -> ByteBuffer.allocate(2).order(order).putShort(v.asBigInt()!!.toShort())
                        'H' -> ByteBuffer.allocate(2).order(order).putShort((v.asBigInt()!!.toInt() and 0xFFFF).toShort())
                        'i', 'l' -> ByteBuffer.allocate(4).order(order).putInt(v.asBigInt()!!.toInt())
                        'I', 'L' -> ByteBuffer.allocate(4).order(order).putInt((v.asBigInt()!!.toLong() and 0xFFFFFFFFL).toInt())
                        'q' -> ByteBuffer.allocate(8).order(order).putLong(v.asBigInt()!!.toLong())
                        'Q' -> ByteBuffer.allocate(8).order(order).putLong(v.asBigInt()!!.toLong())
                        'f' -> ByteBuffer.allocate(4).order(order).putFloat(v.asDouble()!!.toFloat())
                        'd' -> ByteBuffer.allocate(8).order(order).putDouble(v.asDouble()!!)
                        else -> throw PyException("ValueError", "bad char in struct format: '$code'")
                    }
                    out.write(buf.array())
                }
            }
        }
        return out.toByteArray()
    }

    private fun structUnpack(fmt: String, data: ByteArray): List<PyValue> {
        val (order, items) = parseStructSpec(fmt)
        val expectedSize = structCalcSize(fmt)
        if (data.size != expectedSize) {
            throw PyException("ValueError", "unpack requires a buffer of $expectedSize bytes (got ${data.size})")
        }
        val bb = ByteBuffer.wrap(data).order(order)
        val out = mutableListOf<PyValue>()
        for ((count, code) in items) {
            when (code) {
                'x' -> bb.position(bb.position() + count)
                's' -> {
                    val arr = ByteArray(count)
                    bb.get(arr)
                    out.add(PyBytes(arr))
                }
                else -> repeat(count) {
                    val v: PyValue = when (code) {
                        'b' -> PyInt(bb.get().toLong())
                        'B' -> PyInt((bb.get().toInt() and 0xFF).toLong())
                        '?' -> PyBool.of(bb.get().toInt() != 0)
                        'h' -> PyInt(bb.short.toLong())
                        'H' -> PyInt((bb.short.toInt() and 0xFFFF).toLong())
                        'i', 'l' -> PyInt(bb.int.toLong())
                        'I', 'L' -> PyInt(bb.int.toLong() and 0xFFFFFFFFL)
                        'q' -> PyInt(bb.long)
                        'Q' -> {
                            val raw = ByteArray(8)
                            bb.get(raw)
                            PyInt(PyMethods.bytesToBigInt(raw, if (order == ByteOrder.BIG_ENDIAN) "big" else "little", false))
                        }
                        'f' -> PyFloat(bb.float.toDouble())
                        'd' -> PyFloat(bb.double)
                        else -> throw PyException("ValueError", "bad char in struct format: '$code'")
                    }
                    out.add(v)
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // hmac module
    // -------------------------------------------------------------------------
    fun createHmacModule(): PyModule {
        val mod = PyModule("hmac")
        val hmacCls = PyClass("HMAC", emptyList())

        mod.members["new"] = PyNativeFunction("new") { _, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "hmac.new() missing required argument 'key'")
            val keyBytes = PyMethods.extractBytes(args[0])
            val msgVal = kwargs["msg"] ?: args.getOrNull(1)
            val digestSpec = kwargs["digestmod"] ?: args.getOrNull(2)
            val algoName = when (digestSpec) {
                is PyStr -> digestSpec.value.lowercase()
                is PyNativeFunction -> digestSpec.name.lowercase()
                else -> "sha256"
            }
            val jcaAlgo = when (algoName) {
                "sha256", "sha-256" -> "HmacSHA256"
                "sha512", "sha-512" -> "HmacSHA512"
                "sha1", "sha-1" -> "HmacSHA1"
                "md5" -> "HmacMD5"
                else -> "HmacSHA256"
            }
            val buffer = ByteArrayOutputStream()
            if (msgVal != null && msgVal !is PyNone) {
                buffer.write(PyMethods.extractBytes(msgVal))
            }
            fun computeDigest(): ByteArray {
                val mac = Mac.getInstance(jcaAlgo)
                mac.init(SecretKeySpec(keyBytes, jcaAlgo))
                return mac.doFinal(buffer.toByteArray())
            }
            val inst = PyInstance(hmacCls)
            inst.fields["update"] = PyNativeFunction("update") { _, uArgs, _ ->
                buffer.write(PyMethods.extractBytes(uArgs[0]))
                PyNone
            }
            inst.fields["digest"] = PyNativeFunction("digest") { _, _, _ ->
                PyBytes(computeDigest())
            }
            inst.fields["hexdigest"] = PyNativeFunction("hexdigest") { _, _, _ ->
                PyStr(computeDigest().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
            }
            inst
        }

        mod.members["compare_digest"] = PyNativeFunction("compare_digest") { _, args, _ ->
            PyBuiltins.requireArgs("compare_digest", args, 2)
            val a = PyMethods.extractBytes(args[0])
            val b = PyMethods.extractBytes(args[1])
            PyBool.of(MessageDigest.isEqual(a, b))
        }
        return mod
    }

    // -------------------------------------------------------------------------
    // socket module
    // -------------------------------------------------------------------------
    fun createSocketModule(): PyModule {
        val mod = PyModule("socket")
        mod.members["AF_INET"] = PyInt(2)
        mod.members["AF_INET6"] = PyInt(10)
        mod.members["SOCK_STREAM"] = PyInt(1)
        mod.members["SOCK_DGRAM"] = PyInt(2)
        mod.members["IPPROTO_TCP"] = PyInt(6)
        mod.members["TCP_NODELAY"] = PyInt(1)
        mod.members["SOL_SOCKET"] = PyInt(1)
        mod.members["SO_REUSEADDR"] = PyInt(2)

        mod.members["inet_aton"] = PyNativeFunction("inet_aton") { _, args, _ ->
            PyBuiltins.requireArgs("inet_aton", args, 1)
            val s = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "inet_aton() argument must be str")
            PyBytes(parseStrictIpv4(s))
        }

        mod.members["inet_ntoa"] = PyNativeFunction("inet_ntoa") { _, args, _ ->
            PyBuiltins.requireArgs("inet_ntoa", args, 1)
            val b = PyMethods.extractBytes(args[0])
            if (b.size != 4) throw PyException("OSError", "packed IP wrong length for inet_ntoa")
            PyStr("${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}")
        }

        mod.members["inet_pton"] = PyNativeFunction("inet_pton") { _, args, _ ->
            PyBuiltins.requireArgs("inet_pton", args, 2)
            val family = PyBuiltins.requireInt("inet_pton", args[0]).toInt()
            val ip = (args[1] as? PyStr)?.value ?: throw PyException("TypeError", "inet_pton() address must be str")
            when (family) {
                2 -> PyBytes(parseStrictIpv4(ip))
                10 -> PyBytes(parseStrictIpv6(ip))
                else -> throw PyException("OSError", "unknown address family $family")
            }
        }

        mod.members["inet_ntop"] = PyNativeFunction("inet_ntop") { _, args, _ ->
            PyBuiltins.requireArgs("inet_ntop", args, 2)
            val family = PyBuiltins.requireInt("inet_ntop", args[0]).toInt()
            val raw = PyMethods.extractBytes(args[1])
            when (family) {
                2 -> {
                    if (raw.size != 4) throw PyException("ValueError", "invalid length of packed IP address string")
                    PyStr(InetAddress.getByAddress(raw).hostAddress ?: "")
                }
                10 -> {
                    if (raw.size != 16) throw PyException("ValueError", "invalid length of packed IPv6 address string")
                    PyStr(InetAddress.getByAddress(raw).hostAddress ?: "")
                }
                else -> throw PyException("ValueError", "unknown address family $family")
            }
        }

        mod.members["gethostbyname"] = PyNativeFunction("gethostbyname") { _, args, _ ->
            val host = (args[0] as PyStr).value
            val addr = withContext(Dispatchers.IO) { InetAddress.getByName(host).hostAddress ?: host }
            PyStr(addr)
        }
        return mod
    }

    private fun parseStrictIpv4(ip: String): ByteArray {
        val parts = ip.split('.')
        if (parts.size != 4) throw PyException("OSError", "illegal IP address string passed to inet_aton/inet_pton")
        val out = ByteArray(4)
        for (i in 0 until 4) {
            if (parts[i].isEmpty() || !parts[i].all { it.isDigit() }) {
                throw PyException("OSError", "illegal IPv4 address: '$ip'")
            }
            val n = parts[i].toIntOrNull() ?: throw PyException("OSError", "illegal IPv4 address: '$ip'")
            if (n !in 0..255) throw PyException("OSError", "illegal IPv4 address: '$ip'")
            out[i] = n.toByte()
        }
        return out
    }

    private fun parseStrictIpv6(ip: String): ByteArray {
        if (!ip.contains(':')) throw PyException("OSError", "illegal IPv6 address: '$ip'")
        val stripped = ip.removePrefix("[").removeSuffix("]")
        val allowed = stripped.all { it in "0123456789abcdefABCDEF:." }
        if (!allowed) throw PyException("OSError", "illegal IPv6 address: '$ip'")
        return try {
            val addr = InetAddress.getByName(stripped)
            if (addr is Inet6Address) addr.address
            else throw PyException("OSError", "not an IPv6 address: '$ip'")
        } catch (e: PyException) {
            throw e
        } catch (_: Exception) {
            throw PyException("OSError", "illegal IPv6 address: '$ip'")
        }
    }

    private fun wrapJavaSocket(sock: Socket): PyInstance {
        val cls = PyClass("socket", emptyList())
        val inst = PyInstance(cls)
        inst.fields["setsockopt"] = PyNativeFunction("setsockopt") { _, args, _ ->
            val level = args.getOrNull(0)?.asBigInt()?.toInt() ?: 0
            val opt = args.getOrNull(1)?.asBigInt()?.toInt() ?: 0
            val value = args.getOrNull(2)?.asBigInt()?.toInt() ?: 0
            try {
                if (level == 6 && opt == 1) { // IPPROTO_TCP, TCP_NODELAY
                    sock.tcpNoDelay = value != 0
                }
            } catch (_: Exception) {
            }
            PyNone
        }
        inst.fields["close"] = PyNativeFunction("close") { _, _, _ ->
            try { sock.close() } catch (_: Exception) {}
            PyNone
        }
        return inst
    }

    // -------------------------------------------------------------------------
    // asyncio module (real Kotlin Coroutines + real TCP ServerSocket/Socket)
    // -------------------------------------------------------------------------
    fun createAsyncioModule(): PyModule {
        val mod = PyModule("asyncio")

        mod.members["set_event_loop_policy"] = PyNativeFunction("set_event_loop_policy") { _, _, _ -> PyNone }

        mod.members["run"] = PyNativeFunction("run") { _, args, _ ->
            PyBuiltins.requireArgs("asyncio.run", args, 1)
            val arg = args[0]
            if (arg is PyCoroutine) arg.awaitResult() else arg
        }

        mod.members["sleep"] = PyNativeFunction("sleep") { _, args, _ ->
            val secs = args.firstOrNull()?.asDouble() ?: 0.0
            val result = args.getOrNull(1) ?: PyNone
            PyCoroutine("sleep") {
                delay((secs * 1000.0).toLong().coerceAtLeast(0L))
                result
            }
        }

        mod.members["wait_for"] = PyNativeFunction("wait_for") { _, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "wait_for() missing coroutine")
            val coro = args[0]
            val timeoutVal = kwargs["timeout"] ?: args.getOrNull(1)
            val timeoutMs = timeoutVal?.asDouble()?.let { (it * 1000.0).toLong().coerceAtLeast(1L) }
            PyCoroutine("wait_for") {
                try {
                    if (timeoutMs != null) {
                        withTimeout(timeoutMs) {
                            if (coro is PyCoroutine) coro.awaitResult() else coro
                        }
                    } else {
                        if (coro is PyCoroutine) coro.awaitResult() else coro
                    }
                } catch (_: TimeoutCancellationException) {
                    throw PyException("TimeoutError", "asyncio.wait_for timed out after ${timeoutVal?.toDisplayString()}s")
                }
            }
        }

        mod.members["gather"] = PyNativeFunction("gather") { _, args, kwargs ->
            val returnExceptions = kwargs["return_exceptions"]?.toBool() ?: false
            PyCoroutine("gather") {
                val results = coroutineScope {
                    args.map { item ->
                        async {
                            try {
                                if (item is PyCoroutine) item.awaitResult() else item
                            } catch (e: PyException) {
                                if (returnExceptions) PyStr(e.message ?: e.typeName) else throw e
                            }
                        }
                    }.awaitAll()
                }
                PyList(results.toMutableList())
            }
        }

        mod.members["start_server"] = PyNativeFunction("start_server") { eval, args, kwargs ->
            if (args.size < 3) throw PyException("TypeError", "start_server() requires client_connected_cb, host, port")
            val callback = args[0]
            val host = (args[1] as? PyStr)?.value ?: "127.0.0.1"
            val port = PyBuiltins.requireInt("start_server", args[2]).toInt()
            val backlog = (kwargs["backlog"] ?: args.getOrNull(3))?.asBigInt()?.toInt() ?: 128

            PyCoroutine("start_server") {
                val serverSocket = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        soTimeout = 300
                        bind(InetSocketAddress(host, port), backlog)
                    }
                }
                createAsyncServerObject(serverSocket, callback, eval)
            }
        }

        mod.members["open_connection"] = PyNativeFunction("open_connection") { _, args, _ ->
            if (args.size < 2) throw PyException("TypeError", "open_connection() requires host and port")
            val host = (args[0] as PyStr).value
            val port = PyBuiltins.requireInt("open_connection", args[1]).toInt()
            PyCoroutine("open_connection") {
                val sock = withContext(Dispatchers.IO) {
                    Socket().apply {
                        connect(InetSocketAddress(host, port), 15_000)
                    }
                }
                val (reader, writer) = createStreamPair(sock)
                PyTuple(listOf(reader, writer))
            }
        }

        return mod
    }

    private fun createAsyncServerObject(
        serverSocket: ServerSocket,
        callback: PyValue,
        eval: PyEvaluator
    ): PyInstance {
        val cls = PyClass("Server", emptyList())
        val inst = PyInstance(cls)

        fun closeServer() {
            try { if (!serverSocket.isClosed) serverSocket.close() } catch (_: Exception) {}
        }

        inst.fields["close"] = PyNativeFunction("close") { _, _, _ ->
            closeServer()
            PyNone
        }
        inst.fields["wait_closed"] = PyNativeFunction("wait_closed") { _, _, _ ->
            PyCoroutine("wait_closed") { closeServer(); PyNone }
        }
        inst.fields["__aenter__"] = PyNativeFunction("__aenter__") { _, _, _ ->
            PyCoroutine("__aenter__") { inst }
        }
        inst.fields["__aexit__"] = PyNativeFunction("__aexit__") { _, _, _ ->
            PyCoroutine("__aexit__") { closeServer(); PyNone }
        }
        inst.fields["serve_forever"] = PyNativeFunction("serve_forever") { _, _, _ ->
            PyCoroutine("serve_forever") {
                coroutineScope {
                    // Ensure ServerSocket is closed immediately when this coroutine is cancelled (Stop button)
                    val closer = launch {
                        try {
                            awaitCancellationSafely()
                        } finally {
                            closeServer()
                        }
                    }
                    try {
                        while (isActive && !serverSocket.isClosed) {
                            val client = try {
                                withContext(Dispatchers.IO) { serverSocket.accept() }
                            } catch (_: SocketTimeoutException) {
                                continue
                            } catch (e: Exception) {
                                if (!isActive || serverSocket.isClosed) break
                                continue
                            }
                            launch(Dispatchers.IO) {
                                try {
                                    val (reader, writer) = createStreamPair(client)
                                    val res = eval.callValue(callback, listOf(reader, writer), emptyMap(), 0)
                                    if (res is PyCoroutine) res.awaitResult()
                                } catch (_: CancellationException) {
                                    // Stop requested
                                } catch (_: Exception) {
                                    // Connection handler error isolated per client
                                } finally {
                                    try { client.close() } catch (_: Exception) {}
                                }
                            }
                        }
                    } finally {
                        closer.cancel()
                        closeServer()
                    }
                }
                PyNone
            }
        }
        return inst
    }

    private suspend fun awaitCancellationSafely() {
        kotlinx.coroutines.suspendCancellableCoroutine<Nothing> { }
    }

    private fun createStreamPair(sock: Socket): Pair<PyInstance, PyInstance> {
        try { sock.soTimeout = 250 } catch (_: Exception) {}
        val input = BufferedInputStream(sock.getInputStream())
        val output = sock.getOutputStream()
        val writeMutex = Mutex()
        val bufferedBytes = AtomicInteger(0)
        val wrappedSocket = wrapJavaSocket(sock)

        // Transport
        val transportCls = PyClass("_SelectorSocketTransport", emptyList())
        val transport = PyInstance(transportCls)
        transport.fields["write"] = PyNativeFunction("write") { _, args, _ ->
            val data = PyMethods.extractBytes(args[0])
            bufferedBytes.addAndGet(data.size)
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    output.write(data)
                    output.flush()
                    bufferedBytes.addAndGet(-data.size)
                }
            }
            PyNone
        }
        transport.fields["get_write_buffer_size"] = PyNativeFunction("get_write_buffer_size") { _, _, _ ->
            PyInt(bufferedBytes.get().coerceAtLeast(0))
        }
        transport.fields["get_extra_info"] = PyNativeFunction("get_extra_info") { _, args, _ ->
            val key = (args.firstOrNull() as? PyStr)?.value
            if (key == "socket") wrappedSocket else args.getOrNull(1) ?: PyNone
        }

        // StreamReader
        val readerCls = PyClass("StreamReader", emptyList())
        val reader = PyInstance(readerCls)
        reader.fields["read"] = PyNativeFunction("read") { _, args, _ ->
            val maxBytes = args.firstOrNull()?.asBigInt()?.toInt() ?: 65536
            PyCoroutine("StreamReader.read") {
                val data = withContext(Dispatchers.IO) {
                    val size = if (maxBytes <= 0) 65536 else maxBytes.coerceAtMost(262144)
                    val buf = ByteArray(size)
                    var n = -1
                    while (currentCoroutineContext().isActive) {
                        try {
                            n = input.read(buf)
                            break
                        } catch (_: SocketTimeoutException) {
                            currentCoroutineContext().ensureActive()
                        } catch (_: Exception) {
                            n = -1
                            break
                        }
                    }
                    if (n <= 0) ByteArray(0) else buf.copyOf(n)
                }
                PyBytes(data)
            }
        }
        reader.fields["readexactly"] = PyNativeFunction("readexactly") { _, args, _ ->
            val n = PyBuiltins.requireInt("readexactly", args[0]).toInt()
            PyCoroutine("StreamReader.readexactly") {
                val buf = ByteArray(n)
                withContext(Dispatchers.IO) {
                    var offset = 0
                    while (offset < n) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            input.read(buf, offset, n - offset)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (read == -1) throw PyException("EOFError", "IncompleteReadError: $offset bytes read of $n expected")
                        offset += read
                    }
                }
                PyBytes(buf)
            }
        }
        reader.fields["readline"] = PyNativeFunction("readline") { _, _, _ ->
            PyCoroutine("StreamReader.readline") {
                val baos = ByteArrayOutputStream()
                withContext(Dispatchers.IO) {
                    while (currentCoroutineContext().isActive) {
                        val b = try {
                            input.read()
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (b == -1) break
                        baos.write(b)
                        if (b == '\n'.code) break
                    }
                }
                PyBytes(baos.toByteArray())
            }
        }

        // StreamWriter
        val writerCls = PyClass("StreamWriter", emptyList())
        val writer = PyInstance(writerCls)
        writer.fields["transport"] = transport
        writer.fields["write"] = transport.fields["write"]!!
        writer.fields["drain"] = PyNativeFunction("drain") { _, _, _ ->
            PyCoroutine("StreamWriter.drain") {
                withContext(Dispatchers.IO) {
                    writeMutex.withLock { output.flush() }
                }
                PyNone
            }
        }
        writer.fields["close"] = PyNativeFunction("close") { _, _, _ ->
            try { sock.close() } catch (_: Exception) {}
            PyNone
        }
        writer.fields["wait_closed"] = PyNativeFunction("wait_closed") { _, _, _ ->
            PyCoroutine("StreamWriter.wait_closed") {
                try { sock.close() } catch (_: Exception) {}
                PyNone
            }
        }
        writer.fields["get_extra_info"] = PyNativeFunction("get_extra_info") { _, args, _ ->
            val key = (args.firstOrNull() as? PyStr)?.value
            if (key == "socket") wrappedSocket else args.getOrNull(1) ?: PyNone
        }

        return reader to writer
    }

    // -------------------------------------------------------------------------
    // websockets module (Real RFC 6455 Client with TLS SNI + Custom Host + Subprotocols)
    // -------------------------------------------------------------------------
    fun createWebSocketsModule(): PyModule {
        val mod = PyModule("websockets")
        mod.members["__version__"] = PyStr("14.2")

        mod.members["connect"] = PyNativeFunction("connect") { eval, args, kwargs ->
            if (args.isEmpty()) throw PyException("TypeError", "websockets.connect() requires uri")
            val url = (args[0] as? PyStr)?.value ?: throw PyException("TypeError", "uri must be str")
            val openTimeoutSec = kwargs["open_timeout"]?.asDouble() ?: 15.0
            val serverHostname = (kwargs["server_hostname"] as? PyStr)?.value
            val subprotocols = kwargs["subprotocols"]?.let { sp ->
                eval.toIterableList(sp).map { it.toDisplayString() }
            } ?: emptyList()

            val headers = linkedMapOf<String, String>()
            val rawHeaders = kwargs["additional_headers"] ?: kwargs["extra_headers"]
            if (rawHeaders is PyDict) {
                for ((k, v) in rawHeaders.entries) headers[k.value.toDisplayString()] = v.toDisplayString()
            } else if (rawHeaders is PyFrozenDict) {
                for ((k, v) in rawHeaders.entries) headers[k.value.toDisplayString()] = v.toDisplayString()
            }

            PyCoroutine("websockets.connect") {
                withContext(Dispatchers.IO) {
                    openRealWebSocket(
                        url = url,
                        serverHostname = serverHostname,
                        headers = headers,
                        subprotocols = subprotocols,
                        timeoutMs = (openTimeoutSec * 1000.0).toInt().coerceIn(1000, 60_000)
                    )
                }
            }
        }
        return mod
    }

    private fun openRealWebSocket(
        url: String,
        serverHostname: String?,
        headers: Map<String, String>,
        subprotocols: List<String>,
        timeoutMs: Int
    ): PyInstance {
        val uri = URI(url)
        val isTls = uri.scheme.equals("wss", ignoreCase = true)
        val connectHost = uri.host ?: throw PyException("ValueError", "Invalid WebSocket URL: $url")
        val port = if (uri.port > 0) uri.port else if (isTls) 443 else 80
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append("?${uri.rawQuery}")
        }

        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(connectHost, port), timeoutMs)
        rawSocket.soTimeout = timeoutMs

        val sock: Socket = if (isTls) {
            val sniName = serverHostname ?: headers.entries.firstOrNull { it.key.equals("Host", true) }?.value ?: connectHost
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSock = factory.createSocket(rawSocket, sniName, port, true) as SSLSocket
            try {
                val params = sslSock.sslParameters
                if (sniName.any { it.isLetter() }) {
                    params.serverNames = listOf(SNIHostName(sniName))
                }
                sslSock.sslParameters = params
            } catch (_: Exception) {
            }
            sslSock.startHandshake()
            sslSock
        } else {
            rawSocket
        }

        val out = sock.getOutputStream()
        val inp = BufferedInputStream(sock.getInputStream())

        // RFC 6455 HTTP/1.1 Upgrade Handshake
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = Base64.getEncoder().encodeToString(keyBytes)
        val hostHeader = headers.entries.firstOrNull { it.key.equals("Host", true) }?.value
            ?: serverHostname
            ?: if ((isTls && port == 443) || (!isTls && port == 80)) connectHost else "$connectHost:$port"

        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (subprotocols.isNotEmpty()) {
                append("Sec-WebSocket-Protocol: ${subprotocols.joinToString(", ")}\r\n")
            }
            for ((k, v) in headers) {
                if (!k.equals("Host", true)) {
                    append("$k: $v\r\n")
                }
            }
            append("\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        // Read HTTP status line + headers until \r\n\r\n
        val statusLine = readAsciiLine(inp)
        if (!statusLine.contains(" 101 ")) {
            try { sock.close() } catch (_: Exception) {}
            throw PyException("ConnectionError", "WebSocket handshake failed: $statusLine")
        }
        while (true) {
            val line = readAsciiLine(inp)
            if (line.isEmpty()) break
        }

        // Use short read timeout after connect so coroutine cancellation and wait_for stay responsive
        sock.soTimeout = 250

        return createWebSocketObject(sock, inp, out)
    }

    private fun readAsciiLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) throw EOFException("Unexpected EOF during WebSocket handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun createWebSocketObject(
        sock: Socket,
        inp: InputStream,
        out: OutputStream
    ): PyInstance {
        val sendMutex = Mutex()
        val recvMutex = Mutex()
        val rng = SecureRandom()
        var closed = false

        val wrappedSocket = wrapJavaSocket(sock)
        val transportCls = PyClass("WebSocketTransport", emptyList())
        val transport = PyInstance(transportCls)
        transport.fields["get_extra_info"] = PyNativeFunction("get_extra_info") { _, args, _ ->
            val key = (args.firstOrNull() as? PyStr)?.value
            if (key == "socket") wrappedSocket else args.getOrNull(1) ?: PyNone
        }

        suspend fun writeFrame(opcode: Int, payload: ByteArray) {
            sendMutex.withLock {
                withContext(Dispatchers.IO) {
                    val mask = ByteArray(4).also { rng.nextBytes(it) }
                    val header = ByteArrayOutputStream()
                    header.write(0x80 or (opcode and 0x0F))
                    val len = payload.size
                    when {
                        len <= 125 -> header.write(0x80 or len)
                        len <= 65535 -> {
                            header.write(0x80 or 126)
                            header.write((len ushr 8) and 0xFF)
                            header.write(len and 0xFF)
                        }
                        else -> {
                            header.write(0x80 or 127)
                            val bb = ByteBuffer.allocate(8).putLong(len.toLong()).array()
                            header.write(bb)
                        }
                    }
                    header.write(mask)
                    val masked = ByteArray(len) { i ->
                        (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                    out.write(header.toByteArray())
                    out.write(masked)
                    out.flush()
                }
            }
        }

        suspend fun readExact(n: Int): ByteArray = withContext(Dispatchers.IO) {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                currentCoroutineContext().ensureActive()
                val r = try {
                    inp.read(buf, off, n - off)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (r == -1) throw EOFException("WebSocket closed")
                off += r
            }
            buf
        }

        suspend fun readMessage(): PyValue? {
            recvMutex.withLock {
                val fragOut = ByteArrayOutputStream()
                var msgOpcode = -1
                while (!closed) {
                    val b0: Int
                    val b1: Int
                    try {
                        val hdr = readExact(2)
                        b0 = hdr[0].toInt() and 0xFF
                        b1 = hdr[1].toInt() and 0xFF
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        closed = true
                        return null
                    }
                    val fin = (b0 and 0x80) != 0
                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var payloadLen = (b1 and 0x7F).toLong()
                    if (payloadLen == 126L) {
                        val ext = readExact(2)
                        payloadLen = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
                    } else if (payloadLen == 127L) {
                        val ext = readExact(8)
                        payloadLen = ByteBuffer.wrap(ext).long
                    }
                    val maskKey = if (masked) readExact(4) else null
                    val payload = if (payloadLen > 0) {
                        readExact(payloadLen.toInt())
                    } else ByteArray(0)
                    if (maskKey != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        0x8 -> { // Close
                            closed = true
                            try { writeFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
                            try { sock.close() } catch (_: Exception) {}
                            return null
                        }
                        0x9 -> { // Ping -> respond with Pong
                            try { writeFrame(0xA, payload) } catch (_: Exception) {}
                        }
                        0xA -> { /* Pong -> ignore */ }
                        0x0, 0x1, 0x2 -> {
                            if (opcode != 0x0) msgOpcode = opcode
                            fragOut.write(payload)
                            if (fin) {
                                val data = fragOut.toByteArray()
                                return if (msgOpcode == 0x1) PyStr(String(data, Charsets.UTF_8)) else PyBytes(data)
                            }
                        }
                    }
                }
                return null
            }
        }

        val wsCls = PyClass("ClientConnection", emptyList())
        val ws = PyInstance(wsCls)
        ws.fields["transport"] = transport
        ws.fields["send"] = PyNativeFunction("send") { _, args, _ ->
            PyBuiltins.requireArgs("ws.send", args, 1)
            val arg = args[0]
            PyCoroutine("ws.send") {
                if (arg is PyStr) {
                    writeFrame(0x1, arg.value.toByteArray(Charsets.UTF_8))
                } else {
                    writeFrame(0x2, PyMethods.extractBytes(arg))
                }
                PyNone
            }
        }
        ws.fields["recv"] = PyNativeFunction("recv") { _, _, _ ->
            PyCoroutine("ws.recv") {
                readMessage() ?: throw PyException("ConnectionError", "WebSocket connection closed")
            }
        }
        ws.fields["close"] = PyNativeFunction("close") { _, _, _ ->
            PyCoroutine("ws.close") {
                if (!closed) {
                    closed = true
                    try { writeFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
                    try { sock.close() } catch (_: Exception) {}
                }
                PyNone
            }
        }
        ws.fields["__aiter__"] = PyNativeFunction("__aiter__") { _, _, _ -> ws }
        ws.fields["__anext__"] = PyNativeFunction("__anext__") { _, _, _ ->
            PyCoroutine("ws.__anext__") {
                readMessage() ?: throw PyException("StopAsyncIteration", "")
            }
        }
        ws.fields["__aenter__"] = PyNativeFunction("__aenter__") { _, _, _ ->
            PyCoroutine("ws.__aenter__") { ws }
        }
        ws.fields["__aexit__"] = PyNativeFunction("__aexit__") { _, _, _ ->
            PyCoroutine("ws.__aexit__") {
                if (!closed) {
                    closed = true
                    try { sock.close() } catch (_: Exception) {}
                }
                PyNone
            }
        }
        return ws
    }
}
