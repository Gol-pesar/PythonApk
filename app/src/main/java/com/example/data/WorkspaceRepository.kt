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
            id = "python_315_tour",
            fileName = "hello_315.py",
            titleFa = "معرفی Python 3.15.2",
            subtitleFa = "تست frozendict، lazy import، sentinel و unpacking در comprehension",
            badge = "3.15.2",
            code = """
# خوش آمدید به راه‌انداز Python 3.15.2 برای اندروید!
import sys
lazy import math  # PEP 810: بارگذاری تنبل تا لحظه استفاده

print("نسخه موتور:", sys.version)
print("کدگذاری پیش‌فرض (PEP 686):", sys.getdefaultencoding())

# 1) PEP 814: دیکشنری تغییرناپذیر frozendict
config = frozendict(runtime="Python 3.15.2", platform="Android", utf8=True)
print("تنظیمات ثابت:", config)

# 2) PEP 661: شیء نگهبان sentinel
MISSING = sentinel("MISSING")
def get_setting(key, default=MISSING):
    val = config.get(key, default)
    return "یافت نشد" if val is MISSING else val

print("مقدار platform:", get_setting("platform"))
print("مقدار theme:", get_setting("theme"))

# 3) PEP 798: باز کردن (Unpacking) مستقیم درون Comprehension
matrix = [[1, 2], [3, 4], [5, 6]]
flat = [*row for row in matrix]
print("ماتریس تخت‌شده:", flat)

# فراخوانی ماژول lazy import شده
print(f"ریشه دوم 144 = {math.sqrt(144):.1f} | عدد پی = {math.pi:.4f}")
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
print(f"سلام {name}! به کنسول Python 3.15.2 خوش آمدی.")

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
    "app": "PyLaunch 3.15",
    "created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "scores": [98, 92, 100, 87],
    "note": "ذخیره‌سازی امن با کدگذاری UTF-8"
}

with open("notes_data.json", "w") as f:
    json.dump(data, f, indent=2)

with open("notes_data.json", "r") as f:
    loaded = json.load(f)

print("داده خوانده‌شده از فایل:")
print(json.dumps(loaded, indent=2))
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
class Shape:
    def __init__(self, name):
        self.name = name

    def area(self):
        return 0

    def __repr__(self):
        return f"<{self.name}: مساحت={self.area():.2f}>"

class Circle(Shape):
    def __init__(self, radius):
        Shape.__init__(self, "دایره")
        self._r = radius

    @property
    def radius(self):
        return self._r

    def area(self):
        import math
        return math.pi * (self._r ** 2)

class Rectangle(Shape):
    def __init__(self, w, h):
        Shape.__init__(self, "مستطیل")
        self.w = w
        self.h = h

    def area(self):
        return self.w * self.h

shapes = [Circle(5), Rectangle(4, 7), Circle(2.5)]
for s in shapes:
    print(s)
""".trimIndent()
        ),
        PythonTemplate(
            id = "profiler_315",
            fileName = "profiler_315.py",
            titleFa = "پروفایلینگ Python 3.15",
            subtitleFa = "سنجش سرعت اجرا با profiling و statistics",
            badge = "PEP 799",
            code = """
import profiling
import statistics

def compute_squares():
    return sum(i * i for i in range(500))

report = profiling.benchmark(compute_squares, iterations=15)
print("گزارش پروفایلر Python 3.15:")
for k, v in report.items():
    print(f"  {k}: {v}")

samples = [14.2, 15.8, 13.9, 16.4, 15.1, 14.7]
print(f"میانگین: {statistics.mean(samples):.2f} | میانه: {statistics.median(samples):.2f}")
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
