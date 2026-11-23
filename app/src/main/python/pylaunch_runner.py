"""High-performance native CPython runtime bridge for PyLaunch Android."""

import ast
import asyncio
import builtins
import ctypes
import gc
import importlib.metadata
import io
import json
import os
import platform
import ssl
import sys
import threading
import time
import traceback

try:
    import certifi

    _CERT_PATH = certifi.where()
    os.environ.setdefault("SSL_CERT_FILE", _CERT_PATH)
    os.environ.setdefault("REQUESTS_CA_BUNDLE", _CERT_PATH)
except Exception:
    _CERT_PATH = ""

_ORIG_STDOUT = sys.stdout
_ORIG_STDERR = sys.stderr
_ORIG_INPUT = builtins.input
_ORIG_RUN_FOREVER = asyncio.BaseEventLoop.run_forever

_STATE_LOCK = threading.RLock()
_THREAD_CALLBACKS = {}  # tid -> callback
_ACTIVE_RUNS = {}  # run_id -> {"tid": int, "loops": set, "cancelled": bool, "callback": cb}
_LATEST_SCRIPT_CALLBACK = None
_REPL_GLOBALS = {"__name__": "__main__", "__doc__": None, "__package__": None}

# Pre-loaded shared SSLContext for websockets wss:// connections.
# Reusing a single parsed certifi CA store eliminates 200-500ms of GIL-blocked PEM parsing
# on EVERY concurrent connection (huge speedup for proxy/burst workloads on Android).
_SHARED_WSS_SSL_CTX = None
_WEBSOCKETS_PATCHED = False


def _get_shared_wss_ssl_context():
    global _SHARED_WSS_SSL_CTX
    if _SHARED_WSS_SSL_CTX is None:
        if _CERT_PATH and os.path.exists(_CERT_PATH):
            _SHARED_WSS_SSL_CTX = ssl.create_default_context(cafile=_CERT_PATH)
        else:
            _SHARED_WSS_SSL_CTX = ssl.create_default_context()
    return _SHARED_WSS_SSL_CTX


def _patch_websockets_android_optimizations():
    """Apply two critical Android/websockets>=14 fixes:
    1. Replace (instead of duplicate) the Host header when additional_headers overrides Host
       (fixes CLEAN_IP -> Cloudflare HTTP 400 rejection in websockets >= 14).
    2. Reuse the pre-parsed shared SSLContext when ssl=True/omitted so concurrent wss://
       handshakes never stall the GIL re-parsing 280KB of certifi PEM certificates.
    """
    global _WEBSOCKETS_PATCHED
    if _WEBSOCKETS_PATCHED:
        return
    try:
        import websockets.asyncio.client as ws_async_client

        # 1) Fix duplicate Host header when additional_headers contains Host
        orig_handshake = ws_async_client.ClientConnection.handshake

        async def _patched_handshake(
            self,
            additional_headers=None,
            user_agent_header=ws_async_client.USER_AGENT,
        ):
            self.request = self.protocol.connect()
            if additional_headers is not None:
                items = (
                    additional_headers.items()
                    if isinstance(additional_headers, dict)
                    else list(additional_headers)
                )
                has_host = any(str(k).lower() == "host" for k, _ in items)
                if has_host and "Host" in self.request.headers:
                    del self.request.headers["Host"]
                self.request.headers.update(additional_headers)
            if user_agent_header is not None:
                self.request.headers.setdefault("User-Agent", user_agent_header)
            async with self.send_context(expected_state=ws_async_client.CONNECTING):
                self.protocol.send_request(self.request)

            await asyncio.wait(
                [self.response_rcvd, self.connection_lost_waiter],
                return_when=asyncio.FIRST_COMPLETED,
            )
            if self.protocol.handshake_exc is not None:
                raise self.protocol.handshake_exc

        ws_async_client.ClientConnection.handshake = _patched_handshake

        # 2) Reuse shared pre-loaded SSLContext for default wss:// connections
        orig_connect_init = ws_async_client.connect.__init__

        def _patched_connect_init(self, uri, *args, **kwargs):
            if "ssl" not in kwargs or kwargs.get("ssl") is True:
                if isinstance(uri, str) and uri.lower().startswith("wss://"):
                    kwargs["ssl"] = _get_shared_wss_ssl_context()
            return orig_connect_init(self, uri, *args, **kwargs)

        ws_async_client.connect.__init__ = _patched_connect_init

        _WEBSOCKETS_PATCHED = True
    except Exception:
        pass


def _resolve_callback():
    tid = threading.get_ident()
    cb = _THREAD_CALLBACKS.get(tid)
    if cb is not None:
        return cb
    return _LATEST_SCRIPT_CALLBACK


class _BatchedCallbackStream(io.TextIOBase):
    """Coalescing stdout/stderr stream that batches rapid writes before crossing JNI,
    preventing print-heavy loops from stalling CPython or flooding Jetpack Compose.
    """

    _FLUSH_INTERVAL_S = 0.035
    _FLUSH_CHARS = 4096

    def __init__(self, is_err: bool, fallback):
        self._is_err = is_err
        self._fallback = fallback
        self._buf_lock = threading.Lock()
        self._buf = []
        self._buf_len = 0
        self._buf_cb = None
        self._last_flush = time.monotonic()
        self._timer_running = False

    @property
    def encoding(self):
        return "utf-8"

    @property
    def errors(self):
        return "replace"

    def isatty(self):
        return False

    def writable(self):
        return True

    def _schedule_flush(self):
        if self._timer_running:
            return
        self._timer_running = True

        def _delayed():
            time.sleep(self._FLUSH_INTERVAL_S)
            self.flush()

        threading.Thread(target=_delayed, name="py-out-flush", daemon=True).start()

    def write(self, s):
        if not s:
            return 0
        text = s if isinstance(s, str) else str(s)
        cb = _resolve_callback()
        if cb is not None:
            to_emit = None
            emit_cb = None
            now = time.monotonic()
            with self._buf_lock:
                if self._buf_cb is not None and self._buf_cb is not cb and self._buf:
                    to_emit = "".join(self._buf)
                    emit_cb = self._buf_cb
                    self._buf.clear()
                    self._buf_len = 0
                self._buf_cb = cb
                self._buf.append(text)
                self._buf_len += len(text)
                should_flush = (
                    self._is_err
                    or self._buf_len >= self._FLUSH_CHARS
                    or (now - self._last_flush) >= self._FLUSH_INTERVAL_S
                )
                if should_flush and to_emit is None:
                    to_emit = "".join(self._buf)
                    emit_cb = cb
                    self._buf.clear()
                    self._buf_len = 0
                    self._last_flush = now
                elif not should_flush:
                    self._schedule_flush()
            if to_emit and emit_cb is not None:
                try:
                    emit_cb.onOutput(to_emit, self._is_err)
                except Exception:
                    pass
            return len(text)
        if self._fallback is not None:
            try:
                return self._fallback.write(text)
            except Exception:
                pass
        return len(text)

    def flush(self):
        to_emit = None
        emit_cb = None
        with self._buf_lock:
            self._timer_running = False
            if self._buf and self._buf_cb is not None:
                to_emit = "".join(self._buf)
                emit_cb = self._buf_cb
                self._buf.clear()
                self._buf_len = 0
                self._last_flush = time.monotonic()
        if to_emit and emit_cb is not None:
            try:
                emit_cb.onOutput(to_emit, self._is_err)
            except Exception:
                pass
        if self._fallback is not None:
            try:
                self._fallback.flush()
            except Exception:
                pass


_STDOUT_STREAM = _BatchedCallbackStream(False, _ORIG_STDOUT)
_STDERR_STREAM = _BatchedCallbackStream(True, _ORIG_STDERR)


def _flush_all_streams():
    _STDOUT_STREAM.flush()
    _STDERR_STREAM.flush()


def _pylaunch_input(prompt=""):
    prompt_str = "" if prompt is None else str(prompt)
    cb = _resolve_callback()
    if cb is not None:
        _flush_all_streams()
        if prompt_str:
            try:
                cb.onOutput(prompt_str, False)
            except Exception:
                pass
        ans = cb.onInput(prompt_str)
        if ans is None:
            raise KeyboardInterrupt("Input cancelled")
        return str(ans)
    return _ORIG_INPUT(prompt_str)


def _tracked_run_forever(self):
    tid = threading.get_ident()
    already_cancelled = False
    with _STATE_LOCK:
        for run_info in _ACTIVE_RUNS.values():
            if run_info["tid"] == tid:
                run_info["loops"].add(self)
                already_cancelled = run_info["cancelled"]
                break
    if already_cancelled:
        self.call_soon(_cancel_loop_tasks, self)
    return _ORIG_RUN_FOREVER(self)


# Install global thread-safe bridges once on module import
sys.stdout = _STDOUT_STREAM
sys.stderr = _STDERR_STREAM
builtins.input = _pylaunch_input
asyncio.BaseEventLoop.run_forever = _tracked_run_forever


def _inject_keyboard_interrupt(tid: int):
    try:
        res = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(tid),
            ctypes.py_object(KeyboardInterrupt),
        )
        if res > 1:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(tid), None)
    except Exception:
        pass


def _cancel_loop_tasks(loop):
    try:
        for task in asyncio.all_tasks(loop):
            if not task.done():
                task.cancel()
    except Exception:
        pass


def _prepare_workspace(workspace_dir: str):
    if workspace_dir:
        os.makedirs(workspace_dir, exist_ok=True)
        try:
            os.chdir(workspace_dir)
        except Exception:
            pass
        if workspace_dir not in sys.path:
            sys.path.insert(0, workspace_dir)


def warm_up():
    """Pre-load shared TLS store, websockets optimizations, and GC tuning off the UI thread."""
    _get_shared_wss_ssl_context()
    _patch_websockets_android_optimizations()
    gc.set_threshold(2500, 25, 25)
    return get_runtime_info()


def run_script(run_id: str, code: str, filename: str, workspace_dir: str, callback):
    global _LATEST_SCRIPT_CALLBACK
    _get_shared_wss_ssl_context()
    _patch_websockets_android_optimizations()
    gc.set_threshold(2500, 25, 25)

    tid = threading.get_ident()
    _prepare_workspace(workspace_dir)

    run_info = {
        "tid": tid,
        "loops": set(),
        "cancelled": False,
        "callback": callback,
    }
    with _STATE_LOCK:
        _THREAD_CALLBACKS[tid] = callback
        _ACTIVE_RUNS[run_id] = run_info
        _LATEST_SCRIPT_CALLBACK = callback

    script_path = os.path.join(workspace_dir, filename) if workspace_dir else filename
    old_argv = sys.argv
    sys.argv = [script_path]

    script_globals = {
        "__name__": "__main__",
        "__file__": script_path,
        "__doc__": None,
        "__package__": None,
        "__builtins__": builtins,
    }

    try:
        compiled = compile(code, filename or "<script>", "exec")
        exec(compiled, script_globals, script_globals)
        _flush_all_streams()
        with _STATE_LOCK:
            was_cancelled = run_info["cancelled"]
        return json.dumps({"status": "stopped" if was_cancelled else "ok", "error": None})
    except (KeyboardInterrupt, asyncio.CancelledError):
        _flush_all_streams()
        return json.dumps({"status": "stopped", "error": None})
    except SystemExit as e:
        _flush_all_streams()
        exit_code = e.code
        if exit_code is None or exit_code == 0:
            return json.dumps({"status": "ok", "error": None})
        return json.dumps({"status": "error", "error": f"SystemExit: {exit_code}"})
    except BaseException:
        _flush_all_streams()
        with _STATE_LOCK:
            was_cancelled = run_info["cancelled"]
        if was_cancelled:
            return json.dumps({"status": "stopped", "error": None})
        tb = traceback.format_exc()
        return json.dumps({"status": "error", "error": tb})
    finally:
        _flush_all_streams()
        sys.argv = old_argv
        with _STATE_LOCK:
            _THREAD_CALLBACKS.pop(tid, None)
            _ACTIVE_RUNS.pop(run_id, None)
            if _LATEST_SCRIPT_CALLBACK is callback:
                _LATEST_SCRIPT_CALLBACK = None


def stop_script(run_id: str):
    with _STATE_LOCK:
        run_info = _ACTIVE_RUNS.get(run_id)
        if run_info is None:
            return False
        run_info["cancelled"] = True
        tid = run_info["tid"]
        loops = list(run_info["loops"])

    had_running_loop = False
    for loop in loops:
        try:
            if not loop.is_closed() and loop.is_running():
                had_running_loop = True
                loop.call_soon_threadsafe(_cancel_loop_tasks, loop)
        except Exception:
            pass

    if not had_running_loop:
        _inject_keyboard_interrupt(tid)
    else:
        def _fallback_interrupt():
            time.sleep(0.35)
            with _STATE_LOCK:
                still_alive = run_id in _ACTIVE_RUNS
            if still_alive:
                _inject_keyboard_interrupt(tid)

        threading.Thread(target=_fallback_interrupt, daemon=True).start()
    return True


def exec_repl(code: str, workspace_dir: str, callback):
    _patch_websockets_android_optimizations()
    tid = threading.get_ident()
    _prepare_workspace(workspace_dir)

    with _STATE_LOCK:
        _THREAD_CALLBACKS[tid] = callback

    try:
        tree = ast.parse(code, "<stdin>", "exec")
        result_repr = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last_expr = tree.body.pop()
            if tree.body:
                exec(compile(tree, "<stdin>", "exec"), _REPL_GLOBALS, _REPL_GLOBALS)
            expr_mod = ast.Expression(body=last_expr.value)
            val = eval(compile(expr_mod, "<stdin>", "eval"), _REPL_GLOBALS, _REPL_GLOBALS)
            if val is not None:
                _REPL_GLOBALS["_"] = val
                result_repr = repr(val)
        else:
            exec(compile(tree, "<stdin>", "exec"), _REPL_GLOBALS, _REPL_GLOBALS)
        _flush_all_streams()
        return json.dumps({"result": result_repr, "error": None})
    except KeyboardInterrupt:
        _flush_all_streams()
        return json.dumps({"result": None, "error": "KeyboardInterrupt"})
    except BaseException:
        _flush_all_streams()
        return json.dumps({"result": None, "error": traceback.format_exc()})
    finally:
        _flush_all_streams()
        with _STATE_LOCK:
            _THREAD_CALLBACKS.pop(tid, None)


def reset_repl():
    _REPL_GLOBALS.clear()
    _REPL_GLOBALS.update({"__name__": "__main__", "__doc__": None, "__package__": None})
    return True


def get_runtime_info():
    ws_ver = "not installed"
    req_ver = "not installed"
    cert_ver = "not installed"
    try:
        import websockets

        ws_ver = getattr(websockets, "__version__", "installed")
    except Exception:
        pass
    try:
        import requests

        req_ver = getattr(requests, "__version__", "installed")
    except Exception:
        pass
    try:
        import certifi as _c

        cert_ver = getattr(_c, "__version__", "installed")
    except Exception:
        pass

    pkgs = []
    try:
        seen = {}
        for dist in importlib.metadata.distributions():
            name = dist.metadata.get("Name") or ""
            ver = dist.version or ""
            if name:
                seen[name.lower()] = f"{name}=={ver}"
        pkgs = [seen[k] for k in sorted(seen.keys())]
    except Exception:
        pass

    info = {
        "python_version": platform.python_version(),
        "full_version": sys.version.split("\n")[0],
        "implementation": platform.python_implementation(),
        "websockets_version": ws_ver,
        "requests_version": req_ver,
        "certifi_version": cert_ver,
        "openssl_version": getattr(ssl, "OPENSSL_VERSION", "OpenSSL"),
        "packages": pkgs,
    }
    return json.dumps(info)
