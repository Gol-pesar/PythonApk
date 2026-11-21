package com.example.python

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonInterpreterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun runPython(
        code: String,
        inputs: List<String> = emptyList()
    ): String {
        val output = StringBuilder()
        var inputIdx = 0
        val evaluator = PyEvaluator(
            workspaceDir = tempFolder.root,
            onStdout = { output.append(it) },
            onInput = { inputs.getOrElse(inputIdx++) { "" } }
        )
        evaluator.executeScript(code, "test.py")
        return output.toString()
    }

    @Test
    fun testPython315Features() = runTest {
        val out = runPython(
            """
            import sys
            lazy import math

            cfg = frozendict(ver="3.15.2", lang="پایتون")
            MISSING = sentinel("MISSING")
            matrix = [[1, 2], [3, 4], [5]]
            flat = [*row for row in matrix]
            merged = {**d for d in [{"a": 1}, {"b": 2}]}

            print(sys.getdefaultencoding())
            print(cfg["ver"], cfg["lang"])
            print(cfg.get("none", MISSING) is MISSING)
            print(flat)
            print(merged["a"] + merged["b"])
            print(int(math.sqrt(81)))
            """.trimIndent()
        )
        assertEquals(
            "utf-8\n3.15.2 پایتون\nTrue\n[1, 2, 3, 4, 5]\n3\n9\n",
            out
        )
    }

    @Test
    fun testFrozenDictImmutability() = runTest {
        val out = runPython(
            """
            d = frozendict(a=1)
            try:
                d["a"] = 2
            except TypeError as e:
                print("IMMUTABLE")
            """.trimIndent()
        )
        assertEquals("IMMUTABLE\n", out)
    }

    @Test
    fun testMatchCaseAndInput() = runTest {
        val out = runPython(
            """
            name = input("Name? ")
            op = input("Op? ")
            match op:
                case "+":
                    res = 10 + 5
                case "*":
                    res = 10 * 5
                case _:
                    res = 0
            print(f"{name}:{res}")
            """.trimIndent(),
            inputs = listOf("Ali", "*")
        )
        assertTrue(out.endsWith("Ali:50\n"))
    }

    @Test
    fun testCacheDecoratorAndClasses() = runTest {
        val out = runPython(
            """
            from functools import cache

            @cache
            def fib(n):
                return n if n < 2 else fib(n - 1) + fib(n - 2)

            class Box:
                def __init__(self, v):
                    self._v = v
                @property
                def value(self):
                    return self._v * 2

            print(fib(30))
            print(Box(21).value)
            """.trimIndent()
        )
        assertEquals("832040\n42\n", out)
    }

    @Test
    fun testFileAndJsonRoundTrip() = runTest {
        val out = runPython(
            """
            import json
            with open("sample.json", "w") as f:
                json.dump({"title": "تست", "items": [1, 2, 3]}, f)
            with open("sample.json", "r") as f:
                loaded = json.load(f)
            print(loaded["title"], sum(loaded["items"]))
            """.trimIndent()
        )
        assertEquals("تست 6\n", out)
    }

    @Test
    fun testSocks5WebSocketScriptCryptoAndParsing() = runTest {
        val out = runPython(
            """
            #!/usr/bin/env python3
            import asyncio, socket, struct, sys, os, hmac, hashlib
            import websockets

            try:
                import uvloop
                asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
            except ImportError:
                pass

            UUID = "63ae8279-fafa-4dd0-b2ef-9d6a4ec3ffdd"
            _WS_MAJOR = int(websockets.__version__.split(".")[0]) if hasattr(websockets, "__version__") else 12
            _HEADERS_KWARG = "additional_headers" if _WS_MAJOR >= 14 else "extra_headers"

            BO = sys.byteorder
            _ifb = int.from_bytes

            def derive_key(nonce):
                tb = UUID.encode()
                return bytes(tb[i % len(tb)] ^ nonce[i] for i in range(16))

            def build_init(host, port, payload=b""):
                nonce = os.urandom(16)
                key = derive_key(nonce)
                try:
                    socket.inet_pton(socket.AF_INET, host); at, addr = 1, socket.inet_aton(host)
                except OSError:
                    try:
                        socket.inet_pton(socket.AF_INET6, host); at, addr = 3, socket.inet_pton(socket.AF_INET6, host)
                    except OSError:
                        hb = host.encode(); at, addr = 2, bytes([len(hb)]) + hb
                body = bytes([1]) + struct.pack("!H", port) + bytes([at]) + addr + payload
                k_exp = key * (len(body) // len(key)) + key[:len(body) % len(key)]
                enc = (_ifb(body, BO) ^ _ifb(k_exp, BO)).to_bytes(len(body), BO)
                return nonce + enc + hmac.new(key, enc, hashlib.sha256).digest()[:8], key

            pkt_ip4, key1 = build_init("1.2.3.4", 443)
            pkt_dom, key2 = build_init("example.com", 80, b"hi")
            mv = memoryview(key1 * 2)
            xored = (_ifb(b"\x05\x00", BO) ^ _ifb(mv[:2], BO)).to_bytes(2, BO)
            roundtrip = (_ifb(xored, BO) ^ _ifb(mv[:2], BO)).to_bytes(2, BO)

            print(_HEADERS_KWARG)
            print(len(pkt_ip4), len(key1))
            print(len(pkt_dom), struct.unpack("!H", struct.pack("!H", 40446))[0])
            print(socket.inet_ntoa(socket.inet_aton("127.0.0.1")))
            print(roundtrip == b"\x05\x00")
            """.trimIndent()
        )
        assertEquals(
            "additional_headers\n32 16\n42 40446\n127.0.0.1\nTrue\n",
            out
        )
    }

    @Test
    fun testAsyncioServerAndGather() = kotlinx.coroutines.runBlocking {
        val out = runPython(
            """
            import asyncio

            async def handle(r, w):
                h = await asyncio.wait_for(r.readexactly(2), timeout=5)
                if h[0] == 5:
                    w.transport.write(b"\x05\x00")
                w.close()

            async def client_task(port):
                await asyncio.sleep(0.05)
                r, w = await asyncio.open_connection("127.0.0.1", port)
                w.transport.write(b"\x05\x01")
                resp = await asyncio.wait_for(r.readexactly(2), timeout=5)
                w.close()
                return resp

            async def main():
                srv = await asyncio.start_server(handle, "127.0.0.1", 45446)
                async with srv:
                    async def stop_later():
                        res = await client_task(45446)
                        print(res == b"\x05\x00")
                        srv.close()
                    await asyncio.gather(srv.serve_forever(), stop_later())

            asyncio.run(main())
            """.trimIndent()
        )
        assertEquals("True\n", out)
    }

    @Test
    fun testFullSocks5WebSocketScriptAndLoopbackWs() = kotlinx.coroutines.runBlocking {
        // Spin up a real local RFC 6455 WebSocket server to verify websockets.connect + SOCKS5 handler end-to-end
        val wsServer = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val wsPort = wsServer.localPort
        val uuid = "63ae8279-fafa-4dd0-b2ef-9d6a4ec3ffdd"

        val serverThread = Thread {
            try {
                wsServer.soTimeout = 5000
                val sock = wsServer.accept()
                sock.use { s ->
                    val inp = java.io.BufferedInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    // Read HTTP upgrade headers
                    var wsKey = ""
                    val sb = StringBuilder()
                    while (true) {
                        sb.clear()
                        while (true) {
                            val b = inp.read()
                            if (b == -1 || b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        val line = sb.toString()
                        if (line.isEmpty()) break
                        if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                            wsKey = line.substringAfter(":").trim()
                        }
                    }
                    val acceptHash = java.security.MessageDigest.getInstance("SHA-1")
                        .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
                    val acceptB64 = java.util.Base64.getEncoder().encodeToString(acceptHash)
                    val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acceptB64\r\nSec-WebSocket-Protocol: chat.v2\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                    out.flush()

                    fun readWsFrame(): ByteArray {
                        val b0 = inp.read()
                        val b1 = inp.read()
                        val masked = (b1 and 0x80) != 0
                        var len = b1 and 0x7F
                        if (len == 126) len = (inp.read() shl 8) or inp.read()
                        val mask = if (masked) ByteArray(4) { inp.read().toByte() } else null
                        val data = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val r = inp.read(data, off, len - off)
                            if (r == -1) break
                            off += r
                        }
                        if (mask != null) {
                            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }
                        return data
                    }

                    fun writeWsBinary(payload: ByteArray) {
                        out.write(0x82)
                        out.write(payload.size)
                        out.write(payload)
                        out.flush()
                    }

                    // 1. Read init packet (16-byte nonce + enc + 8-byte hmac)
                    val initPkt = readWsFrame()
                    val nonce = initPkt.copyOfRange(0, 16)
                    val tb = uuid.toByteArray(Charsets.UTF_8)
                    val key = ByteArray(16) { i -> (tb[i % tb.size].toInt() xor nonce[i].toInt()).toByte() }

                    // 2. Send encrypted HMAC(key, "OK")[:16] ACK
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                    val okDigest = mac.doFinal("OK".toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16)
                    val encAck = ByteArray(16) { i -> (okDigest[i].toInt() xor key[i].toInt()).toByte() }
                    writeWsBinary(encAck)

                    // 3. Echo back encrypted data frame from client
                    val upFrame = readWsFrame()
                    writeWsBinary(upFrame)
                }
            } catch (_: Exception) {
            } finally {
                try { wsServer.close() } catch (_: Exception) {}
            }
        }
        serverThread.start()

        val out = runPython(
            """
            #!/usr/bin/env python3
            import asyncio, socket, struct, sys, os, hmac, hashlib
            import websockets

            try:
                import uvloop
                asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
            except ImportError:
                pass

            UUID        = "$uuid"
            WORKER_HOST = "127.0.0.1"
            WORKER_URL  = f"ws://{WORKER_HOST}:$wsPort/"
            CLEAN_IP    = "127.0.0.1"
            SUBPROTO    = "chat.v2"
            MAX_CHUNK   = 16384
            BUFFER_MAX  = 1048576

            _WS_MAJOR = int(websockets.__version__.split(".")[0]) if hasattr(websockets, "__version__") else 12
            _HEADERS_KWARG = "additional_headers" if _WS_MAJOR >= 14 else "extra_headers"

            BO = sys.byteorder
            _ifb = int.from_bytes

            def derive_key(nonce):
                tb = UUID.encode()
                return bytes(tb[i % len(tb)] ^ nonce[i] for i in range(16))

            def build_init(host, port, payload=b""):
                nonce = os.urandom(16)
                key = derive_key(nonce)
                try:
                    socket.inet_pton(socket.AF_INET, host); at, addr = 1, socket.inet_aton(host)
                except OSError:
                    try:
                        socket.inet_pton(socket.AF_INET6, host); at, addr = 3, socket.inet_pton(socket.AF_INET6, host)
                    except OSError:
                        hb = host.encode(); at, addr = 2, bytes([len(hb)]) + hb
                body = bytes([1]) + struct.pack("!H", port) + bytes([at]) + addr + payload
                k_exp = key * (len(body) // len(key)) + key[:len(body) % len(key)]
                enc = (_ifb(body, BO) ^ _ifb(k_exp, BO)).to_bytes(len(body), BO)
                return nonce + enc + hmac.new(key, enc, hashlib.sha256).digest()[:8], key

            def _tune_ws_sock(ws):
                try:
                    s = ws.transport.get_extra_info("socket")
                    if s:
                        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                except Exception: pass

            async def ws_connect():
                kw = {
                    "max_size": None,
                    "open_timeout": 15,
                    "subprotocols": [SUBPROTO],
                    "server_hostname": WORKER_HOST,
                    "compression": None,
                    "ping_interval": None,
                }
                if CLEAN_IP:
                    ip = f"[{CLEAN_IP}]" if ":" in CLEAN_IP else CLEAN_IP
                    url = WORKER_URL.replace(WORKER_HOST, ip)
                    kw[_HEADERS_KWARG] = {"Host": WORKER_HOST}
                    ws = await websockets.connect(url, **kw)
                    _tune_ws_sock(ws)
                    return ws
                kw[_HEADERS_KWARG] = {}
                ws = await websockets.connect(WORKER_URL, **kw)
                _tune_ws_sock(ws)
                return ws

            async def handler(r, w):
                try:
                    h = await asyncio.wait_for(r.readexactly(2), timeout=5)
                    if h[0] != 5: return
                    await asyncio.wait_for(r.readexactly(h[1]), timeout=5)
                    w.transport.write(b"\x05\x00")

                    q = await asyncio.wait_for(r.readexactly(4), timeout=5)
                    if q[0] != 5 or q[1] != 1: return
                    at = q[3]
                    if at == 1:   host = socket.inet_ntoa(await r.readexactly(4))
                    elif at == 3: host = (await r.readexactly((await r.readexactly(1))[0])).decode()
                    elif at == 4: host = socket.inet_ntop(socket.AF_INET6, await r.readexactly(16))
                    else: return
                    port = struct.unpack("!H", await r.readexactly(2))[0]

                    sock = w.get_extra_info('socket')
                    if sock:
                        try:
                            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                            if hasattr(socket, 'TCP_QUICKACK'):
                                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_QUICKACK, 1)
                        except Exception: pass

                    ws = await ws_connect()
                    init, key = build_init(host, port)
                    await ws.send(init)

                    raw = key * ((MAX_CHUNK // len(key)) + 1)
                    mv_key = memoryview(raw)
                    key_cache = {}

                    ack = await asyncio.wait_for(ws.recv(), timeout=5)
                    ack_l = len(ack)
                    ack_k = _ifb(mv_key[:ack_l], BO)
                    expect = hmac.new(key, b"OK", hashlib.sha256).digest()[:16]
                    if (_ifb(ack, BO) ^ ack_k).to_bytes(ack_l, BO) != expect: return

                    w.transport.write(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")

                    async def up():
                        d = await r.read(MAX_CHUNK)
                        if not d: return
                        l = len(d)
                        k = key_cache.get(l)
                        if k is None:
                            k = _ifb(mv_key[:l], BO)
                            key_cache[l] = k
                        await ws.send((_ifb(d, BO) ^ k).to_bytes(l, BO))

                    async def down():
                        async for m in ws:
                            l = len(m)
                            k = key_cache.get(l)
                            if k is None:
                                k = _ifb(mv_key[:l], BO)
                                key_cache[l] = k
                            w.transport.write((_ifb(m, BO) ^ k).to_bytes(l, BO))
                            if w.transport.get_write_buffer_size() > BUFFER_MAX:
                                await w.drain()
                            break

                    await asyncio.gather(up(), down(), return_exceptions=True)
                    await ws.close()
                finally:
                    w.close()

            async def run_client(port):
                await asyncio.sleep(0.05)
                r, w = await asyncio.open_connection("127.0.0.1", port)
                w.transport.write(b"\x05\x01\x00")
                g = await asyncio.wait_for(r.readexactly(2), timeout=5)
                w.transport.write(b"\x05\x01\x00\x01" + socket.inet_aton("1.1.1.1") + struct.pack("!H", 443))
                rep = await asyncio.wait_for(r.readexactly(10), timeout=5)
                w.transport.write(b"PING1234")
                echo = await asyncio.wait_for(r.readexactly(8), timeout=5)
                w.close()
                print(g == b"\x05\x00", rep[:2] == b"\x05\x00", echo.decode())

            async def main():
                srv = await asyncio.start_server(handler, "127.0.0.1", 45447, backlog=128)
                async with srv:
                    async def driver():
                        await run_client(45447)
                        srv.close()
                    await asyncio.gather(srv.serve_forever(), driver())

            asyncio.run(main())
            """.trimIndent()
        )
        serverThread.join(3000)
        assertEquals("True True PING1234\n", out)
    }
}

