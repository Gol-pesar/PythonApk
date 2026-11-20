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
}
