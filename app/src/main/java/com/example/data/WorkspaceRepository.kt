package com.example.data

import android.content.Context
import java.io.File

data class WorkspaceFile(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isPython: Boolean
)

data class PythonTemplate(
    val id: String,
    val fileName: String,
    val titleFa: String,
    val subtitleFa: String,
    val badge: String,
    val code: String
)

class WorkspaceRepository(context: Context) {

    val workspaceDir: File = File(context.filesDir, "pylaunch_workspace").apply { mkdirs() }

    val templates: List<PythonTemplate> = listOf(
        PythonTemplate(
            id = "cpython_native_check",
            fileName = "hello_cpython.py",
            titleFa = "تست موتور واقعی CPython و websockets",
            subtitleFa = "بررسی نسخه CPython، کتابخانه رسمی websockets، OpenSSL و سرعت اجرا",
            badge = "Native C",
            code = """
# خوش آمدید به موتور واقعی CPython روی اندروید!
import sys
import ssl
import time
import websockets
import requests
import certifi

print("نسخه CPython:", sys.version.split()[0])
print("پلتفرم:", sys.platform, "| کدگذاری:", sys.getdefaultencoding())
print("نسخه websockets (PyPI):", websockets.__version__)
print("نسخه requests (PyPI):", requests.__version__)
print("نسخه OpenSSL:", ssl.OPENSSL_VERSION)
print("مسیر گواهی certifi:", certifi.where())

# بنچمارک سریع رمزنگاری XOR روی ۴ مگابایت داده
t0 = time.perf_counter()
data = bytes(range(256)) * 16384  # 4 MB
key = b"PyLaunchAndroid!" * (len(data) // 16)
enc = (int.from_bytes(data, sys.byteorder) ^ int.from_bytes(key, sys.byteorder)).to_bytes(len(data), sys.byteorder)
dt = (time.perf_counter() - t0) * 1000
print(f"سرعت XOR روی ۴ مگابایت داده نیتیو: {dt:.1f} میلی‌ثانیه (طول: {len(enc):,} بایت)")
""".trimIndent()
        ),
        PythonTemplate(
            id = "websockets_loopback",
            fileName = "ws_echo_test.py",
            titleFa = "تست کامل سرور و کلاینت WebSocket",
            subtitleFa = "اجرای واقعی asyncio + websockets روی لوکال‌هاست اندروید",
            badge = "WebSocket",
            code = """
import asyncio
import websockets

async def echo_handler(ws):
    async for msg in ws:
        await ws.send(f"پاسخ سرور: {msg}")

async def main():
    async with websockets.serve(echo_handler, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        print(f"سرور WebSocket روی 127.0.0.1:{port} آماده شد.")

        async with websockets.connect(f"ws://127.0.0.1:{port}") as client:
            for i in range(1, 4):
                await client.send(f"پیام شماره {i}")
                reply = await client.recv()
                print("دریافت شد ->", reply)

        print("تست بسته رسمی websockets با موفقیت کامل شد!")

asyncio.run(main())
""".trimIndent()
        ),
        PythonTemplate(
            id = "interactive_input",
            fileName = "interactive_calc.py",
            titleFa = "برنامه تعاملی با input()",
            subtitleFa = "دریافت ورودی از کاربر و ساختار match/case",
            badge = "تعاملی",
            code = """
# مثال دریافت ورودی تعاملی و استفاده از match / case
name = input("نام شما چیست؟ ")
print(f"سلام {name}! به کنسول نیتیو پایتون خوش آمدی.")

op = input("عملیات را انتخاب کنید (+, -, *, /): ").strip()
a = float(input("عدد اول: "))
b = float(input("عدد دوم: "))

match op:
    case "+":
        res = a + b
    case "-":
        res = a - b
    case "*":
        res = a * b
    case "/":
        res = "خطای تقسیم بر صفر!" if b == 0 else a / b
    case _:
        res = "عملگر نامعتبر!"

print(f"نتیجه: {a} {op} {b} = {res}")
""".trimIndent()
        ),
        PythonTemplate(
            id = "algorithms_cache",
            fileName = "algorithms.py",
            titleFa = "الگوریتم و @functools.cache",
            subtitleFa = "فیبوناچی با کش حافظه و غربال اعداد اول",
            badge = "الگوریتم",
            code = """
from functools import cache

@cache
def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

print("۲۰ عدد اول دنباله فیبوناچی:")
print([fib(i) for i in range(20)])
print(f"فیبوناچی ۶۰ام (دقت نامحدود): {fib(60):,}")

def primes_up_to(limit):
    return [
        n for n in range(2, limit + 1)
        if all(n % d != 0 for d in range(2, int(n ** 0.5) + 1))
    ]

primes = primes_up_to(60)
print(f"تعداد اعداد اول تا ۶۰: {len(primes)}")
print("اعداد اول:", primes)
""".trimIndent()
        ),
        PythonTemplate(
            id = "files_json",
            fileName = "files_and_json.py",
            titleFa = "کار با فایل و JSON",
            subtitleFa = "ذخیره و خواندن فایل UTF-8 در فضای پروژه اندروید",
            badge = "فایل",
            code = """
import json
import os
from datetime import datetime

data = {
    "app": "PyLaunch",
    "created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "scores": [98, 92, 100, 87],
    "note": "ذخیره‌سازی امن با کدگذاری UTF-8"
}

with open("notes_data.json", "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)

with open("notes_data.json", "r", encoding="utf-8") as f:
    loaded = json.load(f)

print("داده خوانده‌شده از فایل:")
print(json.dumps(loaded, indent=2, ensure_ascii=False))
print("فایل‌های موجود در پوشه پروژه:", os.listdir())
""".trimIndent()
        ),
        PythonTemplate(
            id = "oop_classes",
            fileName = "oop_classes.py",
            titleFa = "برنامه‌نویسی شیءگرا (OOP)",
            subtitleFa = "کلاس، وراثت، property و متدهای جادویی",
            badge = "شیءگرا",
            code = """
import math

class Shape:
    def __init__(self, name):
        self.name = name

    def area(self):
        return 0

    def __repr__(self):
        return f"<{self.name}: مساحت={self.area():.2f}>"

class Circle(Shape):
    def __init__(self, radius):
        super().__init__("دایره")
        self._r = radius

    @property
    def radius(self):
        return self._r

    def area(self):
        return math.pi * (self._r ** 2)

class Rectangle(Shape):
    def __init__(self, w, h):
        super().__init__("مستطیل")
        self.w = w
        self.h = h

    def area(self):
        return self.w * self.h

shapes = [Circle(5), Rectangle(4, 7), Circle(2.5)]
for s in shapes:
    print(s)
""".trimIndent()
        )
    )

    init {
        ensureDefaultScript()
    }

    private fun ensureDefaultScript() {
        val mainFile = File(workspaceDir, "main.py")
        if (!mainFile.exists()) {
            mainFile.writeText(templates.first().code, Charsets.UTF_8)
        }
    }

    fun listFiles(): List<WorkspaceFile> {
        workspaceDir.mkdirs()
        return workspaceDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedWith(compareByDescending<File> { it.name == "main.py" }.thenBy { it.name.lowercase() })
            ?.map { f ->
                WorkspaceFile(
                    name = f.name,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    isPython = f.extension.equals("py", ignoreCase = true)
                )
            } ?: emptyList()
    }

    fun readFile(name: String): String {
        val file = safeFile(name)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun writeFile(name: String, content: String): WorkspaceFile {
        val cleanName = sanitizeName(name)
        val file = safeFile(cleanName)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return WorkspaceFile(
            name = file.name,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            isPython = file.extension.equals("py", ignoreCase = true)
        )
    }

    fun renameFile(oldName: String, newName: String): String {
        val cleanNew = sanitizeName(newName)
        val oldFile = safeFile(oldName)
        val newFile = safeFile(cleanNew)
        if (oldFile.exists() && !newFile.exists()) {
            oldFile.renameTo(newFile)
            return cleanNew
        }
        return oldName
    }

    fun deleteFile(name: String): Boolean {
        val file = safeFile(name)
        return if (file.exists()) file.delete() else false
    }

    fun sanitizeName(raw: String): String {
        val trimmed = raw.trim().replace("/", "_").replace("\\", "_").replace("..", "")
        if (trimmed.isEmpty()) return "script.py"
        return if (!trimmed.contains('.')) "$trimmed.py" else trimmed
    }

    private fun safeFile(name: String): File {
        val candidate = File(workspaceDir, name).canonicalFile
        val root = workspaceDir.canonicalFile
        require(candidate.path.startsWith(root.path)) { "Invalid workspace path" }
        return candidate
    }
}
