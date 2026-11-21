"""Native CPython runtime bridge for PyLaunch Android."""

import ast
import asyncio
import builtins
import ctypes
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


def _resolve_callback():
    tid = threading.get_ident()
    cb = _THREAD_CALLBACKS.get(tid)
    if cb is not None:
        return cb
    return _LATEST_SCRIPT_CALLBACK


class _CallbackStream(io.TextIOBase):
    def __init__(self, is_err: bool, fallback):
        self._is_err = is_err
        self._fallback = fallback

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

    def write(self, s):
        if not s:
            return 0
        text = s if isinstance(s, str) else str(s)
        cb = _resolve_callback()
        if cb is not None:
            try:
                cb.onOutput(text, self._is_err)
                return len(text)
            except Exception:
                pass
        if self._fallback is not None:
            try:
                return self._fallback.write(text)
            except Exception:
                pass
        return len(text)

    def flush(self):
        if self._fallback is not None:
            try:
                self._fallback.flush()
            except Exception:
                pass


def _pylaunch_input(prompt=""):
    prompt_str = "" if prompt is None else str(prompt)
    cb = _resolve_callback()
    if cb is not None:
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
sys.stdout = _CallbackStream(False, _ORIG_STDOUT)
sys.stderr = _CallbackStream(True, _ORIG_STDERR)
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


def run_script(run_id: str, code: str, filename: str, workspace_dir: str, callback):
    global _LATEST_SCRIPT_CALLBACK
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
        with _STATE_LOCK:
            was_cancelled = run_info["cancelled"]
        return json.dumps({"status": "stopped" if was_cancelled else "ok", "error": None})
    except (KeyboardInterrupt, asyncio.CancelledError):
        return json.dumps({"status": "stopped", "error": None})
    except SystemExit as e:
        exit_code = e.code
        if exit_code is None or exit_code == 0:
            return json.dumps({"status": "ok", "error": None})
        return json.dumps({"status": "error", "error": f"SystemExit: {exit_code}"})
    except BaseException:
        with _STATE_LOCK:
            was_cancelled = run_info["cancelled"]
        if was_cancelled:
            return json.dumps({"status": "stopped", "error": None})
        tb = traceback.format_exc()
        return json.dumps({"status": "error", "error": tb})
    finally:
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
        # Give asyncio clean shutdown (closing server sockets) a brief grace window,
        # then fall back to KeyboardInterrupt if the thread is still blocked.
        def _fallback_interrupt():
            time.sleep(0.35)
            with _STATE_LOCK:
                still_alive = run_id in _ACTIVE_RUNS
            if still_alive:
                _inject_keyboard_interrupt(tid)

        threading.Thread(target=_fallback_interrupt, daemon=True).start()
    return True


def exec_repl(code: str, workspace_dir: str, callback):
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
        return json.dumps({"result": result_repr, "error": None})
    except KeyboardInterrupt:
        return json.dumps({"result": None, "error": "KeyboardInterrupt"})
    except BaseException:
        return json.dumps({"result": None, "error": traceback.format_exc()})
    finally:
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
