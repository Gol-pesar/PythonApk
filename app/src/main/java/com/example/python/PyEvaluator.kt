package com.example.python

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File
import java.math.BigInteger
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

class PyEvaluator(
    private val workspaceDir: File,
    private val onStdout: (String) -> Unit,
    private val onInput: suspend (prompt: String) -> String
) {
    val globalEnv = PyEnvironment(isModuleRoot = true)
    var currentEnv: PyEnvironment = globalEnv
        private set
    private val moduleCache = java.util.concurrent.ConcurrentHashMap<String, PyModule>()
    private val activeResources = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.Closeable>()
    private val localTicks = ThreadLocal.withInitial { 0 }
    private var activeFileName = "<main>"

    init {
        PyBuiltins.install(globalEnv, workspaceDir)
        globalEnv.defineLocal("__name__", PyStr("__main__"))
        globalEnv.defineLocal("__version__", PyStr("3.15.2"))
    }

    fun registerResource(res: java.io.Closeable) {
        activeResources.add(res)
    }

    fun unregisterResource(res: java.io.Closeable) {
        activeResources.remove(res)
    }

    fun closeAllResources() {
        val snapshot = activeResources.toList()
        activeResources.clear()
        for (r in snapshot) {
            try { r.close() } catch (_: Exception) {}
        }
    }

    fun emitStdout(text: String) {
        onStdout(text)
    }

    suspend fun requestInput(prompt: String): String {
        if (prompt.isNotEmpty()) onStdout(prompt)
        return onInput(prompt)
    }

    suspend fun executeScript(source: String, fileName: String = "main.py"): PyValue {
        activeFileName = fileName
        globalEnv.defineLocal("__file__", PyStr(fileName))
        val tokens = PyLexer(source, fileName).tokenize()
        val stmts = PyParser(tokens, fileName).parseProgram()
        var lastVal: PyValue = PyNone
        try {
            for (stmt in stmts) {
                lastVal = execStmt(stmt, globalEnv)
            }
        } finally {
            closeAllResources()
        }
        return lastVal
    }

    suspend fun executeRepl(source: String): PyValue? {
        activeFileName = "<stdin>"
        val tokens = PyLexer(source, "<stdin>").tokenize()
        val stmts = PyParser(tokens, "<stdin>").parseProgram()
        if (stmts.size == 1 && stmts[0] is Stmt.ExprStmt) {
            val res = evalExpr((stmts[0] as Stmt.ExprStmt).expr, globalEnv)
            if (res !is PyNone) globalEnv.defineLocal("_", res)
            return res
        }
        for (stmt in stmts) {
            execStmt(stmt, globalEnv)
        }
        return null
    }

    private suspend fun tick() {
        val next = (localTicks.get() ?: 0) + 1
        if ((next and 4095) == 0) {
            localTicks.set(0)
            currentCoroutineContext().ensureActive()
        } else {
            localTicks.set(next)
        }
    }

    suspend fun execStmt(stmt: Stmt, env: PyEnvironment): PyValue {
        tick()
        try {
            return when (stmt) {
                is Stmt.Pass -> PyNone
                is Stmt.Break -> throw BreakSignal()
                is Stmt.Continue -> throw ContinueSignal()
                is Stmt.Return -> throw ReturnSignal(stmt.value?.let { evalExpr(it, env) } ?: PyNone)
                is Stmt.Yield -> throw YieldSignal(stmt.value?.let { evalExpr(it, env) } ?: PyNone)
                is Stmt.ExprStmt -> evalExpr(stmt.expr, env)
                is Stmt.Assign -> {
                    val value = evalExpr(stmt.value, env)
                    for (t in stmt.targets) assignTarget(t, value, env, stmt.line)
                    PyNone
                }
                is Stmt.AugAssign -> {
                    val cur = readTarget(stmt.target, env, stmt.line)
                    val rhs = evalExpr(stmt.value, env)
                    val updated = applyBinaryOp(stmt.op, cur, rhs, stmt.line)
                    assignTarget(stmt.target, updated, env, stmt.line)
                    PyNone
                }
                is Stmt.Del -> {
                    for (t in stmt.targets) deleteTarget(t, env, stmt.line)
                    PyNone
                }
                is Stmt.Global -> {
                    stmt.names.forEach { env.declareGlobal(it) }
                    PyNone
                }
                is Stmt.Nonlocal -> {
                    stmt.names.forEach { env.declareNonlocal(it) }
                    PyNone
                }
                is Stmt.Assert -> {
                    if (!evalExpr(stmt.test, env).toBool()) {
                        val msg = stmt.message?.let { pyToString(evalExpr(it, env)) } ?: ""
                        throw PyException("AssertionError", msg, stmt.line, activeFileName)
                    }
                    PyNone
                }
                is Stmt.Raise -> {
                    val excVal = stmt.exception?.let { evalExpr(it, env) }
                    when (excVal) {
                        null -> throw PyException("RuntimeError", "No active exception to reraise", stmt.line, activeFileName)
                        is PyClass -> throw PyException(excVal.name, "", stmt.line, activeFileName)
                        is PyInstance -> {
                            val msg = excVal.fields["args"]?.let { pyToString(it) } ?: ""
                            throw PyException(excVal.pyClass.name, msg, stmt.line, activeFileName)
                        }
                        else -> throw PyException("Exception", pyToString(excVal), stmt.line, activeFileName)
                    }
                }
                is Stmt.If -> {
                    var matched = false
                    for ((cond, body) in stmt.branches) {
                        if (evalExpr(cond, env).toBool()) {
                            execBlock(body, env)
                            matched = true
                            break
                        }
                    }
                    if (!matched) execBlock(stmt.elseBody, env)
                    PyNone
                }
                is Stmt.While -> {
                    var broke = false
                    while (evalExpr(stmt.condition, env).toBool()) {
                        tick()
                        try {
                            execBlock(stmt.body, env)
                        } catch (_: BreakSignal) {
                            broke = true; break
                        } catch (_: ContinueSignal) {
                            continue
                        }
                    }
                    if (!broke) execBlock(stmt.elseBody, env)
                    PyNone
                }
                is Stmt.For -> {
                    var broke = false
                    if (stmt.isAsync) {
                        val iterableObj = evalExpr(stmt.iterable, env)
                        val aiterFn = try { getAttribute(iterableObj, "__aiter__", stmt.line) } catch (_: PyException) { null }
                        val asyncIter = if (aiterFn != null) {
                            val r = callValue(aiterFn, emptyList(), emptyMap(), stmt.line)
                            if (r is PyCoroutine) r.awaitResult() else r
                        } else iterableObj
                        val anextFn = getAttribute(asyncIter, "__anext__", stmt.line)
                        while (true) {
                            tick()
                            val nextItem = try {
                                val r = callValue(anextFn, emptyList(), emptyMap(), stmt.line)
                                if (r is PyCoroutine) r.awaitResult() else r
                            } catch (e: PyException) {
                                if (e.typeName == "StopAsyncIteration") break else throw e
                            }
                            assignTarget(stmt.target, nextItem, env, stmt.line)
                            try {
                                execBlock(stmt.body, env)
                            } catch (_: BreakSignal) {
                                broke = true; break
                            } catch (_: ContinueSignal) {
                                continue
                            }
                        }
                    } else {
                        val iterVal = evalExpr(stmt.iterable, env)
                        if (iterVal is PyRange) {
                            var cur = iterVal.start
                            val stop = iterVal.stop
                            val step = iterVal.step
                            while (if (step > 0) cur < stop else cur > stop) {
                                tick()
                                assignTarget(stmt.target, PyInt.of(cur), env, stmt.line)
                                try {
                                    execBlock(stmt.body, env)
                                } catch (_: BreakSignal) {
                                    broke = true; break
                                } catch (_: ContinueSignal) {
                                    cur += step; continue
                                }
                                cur += step
                            }
                        } else {
                            val items = toIterableList(iterVal)
                            for (item in items) {
                                tick()
                                assignTarget(stmt.target, item, env, stmt.line)
                                try {
                                    execBlock(stmt.body, env)
                                } catch (_: BreakSignal) {
                                    broke = true; break
                                } catch (_: ContinueSignal) {
                                    continue
                                }
                            }
                        }
                    }
                    if (!broke) execBlock(stmt.elseBody, env)
                    PyNone
                }
                is Stmt.FuncDef -> {
                    val defaults = mutableMapOf<String, PyValue>()
                    for (p in stmt.params) {
                        if (p.defaultExpr != null) defaults[p.name] = evalExpr(p.defaultExpr, env)
                    }
                    val doc = (stmt.body.firstOrNull() as? Stmt.ExprStmt)?.expr
                        ?.let { (it as? Expr.Literal)?.value as? PyStr }?.value
                    var fn: PyValue = PyFunction(
                        name = stmt.name,
                        params = stmt.params,
                        body = stmt.body,
                        closure = env,
                        isGenerator = !stmt.isAsync && containsYield(stmt.body),
                        defaults = defaults,
                        doc = doc,
                        isAsync = stmt.isAsync
                    )
                    for (dec in stmt.decorators.asReversed()) {
                        val decFn = evalExpr(dec, env)
                        fn = callValue(decFn, listOf(fn), emptyMap(), dec.line)
                    }
                    env.set(stmt.name, fn)
                    PyNone
                }
                is Stmt.ClassDef -> {
                    val baseClasses = stmt.bases.map { b ->
                        evalExpr(b, env) as? PyClass
                            ?: throw PyException("TypeError", "Base must be a class", b.line, activeFileName)
                    }
                    val classEnv = PyEnvironment(parent = env)
                    execBlock(stmt.body, classEnv)
                    var cls: PyValue = PyClass(stmt.name, baseClasses, classEnv.values)
                    for (dec in stmt.decorators.asReversed()) {
                        val decFn = evalExpr(dec, env)
                        cls = callValue(decFn, listOf(cls), emptyMap(), dec.line)
                    }
                    env.set(stmt.name, cls)
                    PyNone
                }
                is Stmt.TryStmt -> {
                    var succeeded = false
                    try {
                        execBlock(stmt.body, env)
                        succeeded = true
                    } catch (e: PyException) {
                        var handled = false
                        for (h in stmt.handlers) {
                            val matches = if (h.exceptionType == null) true
                            else matchesException(e, evalExpr(h.exceptionType, env))
                            if (matches) {
                                if (h.alias != null) {
                                    val excCls = PyClass(e.typeName, emptyList())
                                    val excInst = PyInstance(excCls)
                                    excInst.fields["args"] = PyTuple(listOf(PyStr(e.pyMessage)))
                                    excInst.fields["__str__"] = PyNativeFunction("__str__") { _, _, _ -> PyStr(e.pyMessage) }
                                    env.set(h.alias, excInst)
                                }
                                execBlock(h.body, env)
                                handled = true
                                break
                            }
                        }
                        if (!handled) throw e
                    } finally {
                        execBlock(stmt.finallyBody, env)
                    }
                    if (succeeded) execBlock(stmt.elseBody, env)
                    PyNone
                }
                is Stmt.WithStmt -> {
                    val enterAttr = if (stmt.isAsync) "__aenter__" else "__enter__"
                    val exitAttr = if (stmt.isAsync) "__aexit__" else "__exit__"
                    val managers = mutableListOf<PyValue>()
                    for ((ctxExpr, target) in stmt.items) {
                        val mgr = evalExpr(ctxExpr, env)
                        managers.add(mgr)
                        val enterFn = try { getAttribute(mgr, enterAttr, stmt.line) } catch (_: PyException) { null }
                        var bound = if (enterFn != null) callValue(enterFn, emptyList(), emptyMap(), stmt.line) else mgr
                        if (stmt.isAsync && bound is PyCoroutine) bound = bound.awaitResult()
                        if (target != null) assignTarget(target, bound, env, stmt.line)
                    }
                    try {
                        execBlock(stmt.body, env)
                    } finally {
                        for (mgr in managers.asReversed()) {
                            val exitFn = try { getAttribute(mgr, exitAttr, stmt.line) } catch (_: PyException) { null }
                            if (exitFn != null) {
                                val r = callValue(exitFn, listOf(PyNone, PyNone, PyNone), emptyMap(), stmt.line)
                                if (stmt.isAsync && r is PyCoroutine) r.awaitResult()
                            }
                        }
                    }
                    PyNone
                }
                is Stmt.ImportStmt -> {
                    for ((modName, alias) in stmt.names) {
                        val bindName = alias ?: modName.substringBefore('.')
                        val modVal: PyValue = if (stmt.isLazy) {
                            PyLazyModule(modName) { loadModuleOrThrow(modName, stmt.line) }
                        } else {
                            loadModuleOrThrow(modName, stmt.line)
                        }
                        env.set(bindName, modVal)
                    }
                    PyNone
                }
                is Stmt.FromImportStmt -> {
                    val mod = loadModuleOrThrow(stmt.module, stmt.line)
                    for ((itemName, alias) in stmt.names) {
                        if (itemName == "*") {
                            for ((k, v) in mod.members) {
                                if (!k.startsWith("_")) env.set(k, v)
                            }
                        } else {
                            val member = mod.members[itemName]
                                ?: throw PyException("ImportError", "cannot import name '$itemName' from '${stmt.module}'", stmt.line, activeFileName)
                            env.set(alias ?: itemName, member)
                        }
                    }
                    PyNone
                }
                is Stmt.MatchStmt -> {
                    val subjectVal = evalExpr(stmt.subject, env)
                    for (case in stmt.cases) {
                        val bindings = mutableMapOf<String, PyValue>()
                        if (matchPattern(case.pattern, subjectVal, bindings, env)) {
                            val prevBindings = bindings.keys.associateWith { env.values[it] }
                            bindings.forEach { (k, v) -> env.set(k, v) }
                            val guardOk = case.guard == null || evalExpr(case.guard, env).toBool()
                            if (guardOk) {
                                execBlock(case.body, env)
                                break
                            } else {
                                prevBindings.forEach { (k, v) ->
                                    if (v == null) env.values.remove(k) else env.values[k] = v
                                }
                            }
                        }
                    }
                    PyNone
                }
            }
        } catch (ce: CancellationException) {
            throw PyExecutionCancelled()
        } catch (e: PyException) {
            if (e.frames.isEmpty()) e.addFrame(activeFileName, stmt.line, "<module>")
            throw e
        }
    }

    private suspend fun execBlock(stmts: List<Stmt>, env: PyEnvironment) {
        for (s in stmts) execStmt(s, env)
    }

    suspend fun evalExpr(expr: Expr, env: PyEnvironment): PyValue {
        return when (expr) {
            is Expr.Literal -> expr.value
            is Expr.Identifier -> env.get(expr.name)
                ?: throw PyException("NameError", "name '${expr.name}' is not defined", expr.line, activeFileName)
            is Expr.FString -> PyStr(evaluateFString(expr.rawTemplate, env, expr.line))
            is Expr.Unary -> evalUnary(expr.op, evalExpr(expr.operand, env), expr.line)
            is Expr.Binary -> applyBinaryOp(expr.op, evalExpr(expr.left, env), evalExpr(expr.right, env), expr.line)
            is Expr.BoolOp -> {
                if (expr.op == "and") {
                    var res: PyValue = PyBool.TRUE
                    for (v in expr.values) {
                        res = evalExpr(v, env)
                        if (!res.toBool()) return res
                    }
                    res
                } else {
                    var res: PyValue = PyBool.FALSE
                    for (v in expr.values) {
                        res = evalExpr(v, env)
                        if (res.toBool()) return res
                    }
                    res
                }
            }
            is Expr.Compare -> {
                var leftVal = evalExpr(expr.left, env)
                for (i in expr.ops.indices) {
                    val rightVal = evalExpr(expr.comparators[i], env)
                    if (!evalCompareOp(expr.ops[i], leftVal, rightVal, expr.line)) {
                        return PyBool.FALSE
                    }
                    leftVal = rightVal
                }
                PyBool.TRUE
            }
            is Expr.IfExpr -> {
                if (evalExpr(expr.condition, env).toBool()) evalExpr(expr.thenExpr, env)
                else evalExpr(expr.elseExpr, env)
            }
            is Expr.NamedExpr -> {
                val v = evalExpr(expr.value, env)
                env.set(expr.target, v)
                v
            }
            is Expr.Starred -> evalExpr(expr.value, env)
            is Expr.DoubleStarred -> evalExpr(expr.value, env)
            is Expr.ListLiteral -> {
                val out = mutableListOf<PyValue>()
                for (e in expr.elements) {
                    if (e is Expr.Starred) out.addAll(toIterableList(evalExpr(e.value, env)))
                    else out.add(evalExpr(e, env))
                }
                PyList(out)
            }
            is Expr.TupleLiteral -> {
                val out = mutableListOf<PyValue>()
                for (e in expr.elements) {
                    if (e is Expr.Starred) out.addAll(toIterableList(evalExpr(e.value, env)))
                    else out.add(evalExpr(e, env))
                }
                PyTuple(out)
            }
            is Expr.SetLiteral -> {
                val out = linkedSetOf<PyKey>()
                for (e in expr.elements) {
                    if (e is Expr.Starred) {
                        for (item in toIterableList(evalExpr(e.value, env))) {
                            ensureHashable(item); out.add(PyKey(item))
                        }
                    } else {
                        val v = evalExpr(e, env)
                        ensureHashable(v); out.add(PyKey(v))
                    }
                }
                PySet(out)
            }
            is Expr.DictLiteral -> {
                val map = linkedMapOf<PyKey, PyValue>()
                for (entry in expr.entries) {
                    if (entry.key == null) {
                        val unpackVal = evalExpr(entry.value, env)
                        mergeMapping(map, unpackVal, expr.line)
                    } else {
                        val k = evalExpr(entry.key, env)
                        ensureHashable(k)
                        map[PyKey(k)] = evalExpr(entry.value, env)
                    }
                }
                PyDict(map)
            }
            is Expr.ListComp -> {
                val out = mutableListOf<PyValue>()
                evalCompClauses(expr.clauses, 0, PyEnvironment(parent = env)) { compEnv ->
                    // PEP 798 unpacking in list comprehensions: `[*row for row in matrix]`
                    if (expr.element is Expr.Starred) {
                        out.addAll(toIterableList(evalExpr(expr.element.value, compEnv)))
                    } else {
                        out.add(evalExpr(expr.element, compEnv))
                    }
                }
                PyList(out)
            }
            is Expr.SetComp -> {
                val out = linkedSetOf<PyKey>()
                evalCompClauses(expr.clauses, 0, PyEnvironment(parent = env)) { compEnv ->
                    if (expr.element is Expr.Starred) {
                        for (item in toIterableList(evalExpr(expr.element.value, compEnv))) {
                            ensureHashable(item); out.add(PyKey(item))
                        }
                    } else {
                        val v = evalExpr(expr.element, compEnv)
                        ensureHashable(v); out.add(PyKey(v))
                    }
                }
                PySet(out)
            }
            is Expr.DictComp -> {
                val map = linkedMapOf<PyKey, PyValue>()
                evalCompClauses(expr.clauses, 0, PyEnvironment(parent = env)) { compEnv ->
                    // PEP 798 unpacking in dict comprehensions: `{**d for d in dicts}`
                    if (expr.isUnpack) {
                        mergeMapping(map, evalExpr(expr.value, compEnv), expr.line)
                    } else {
                        val k = evalExpr(expr.key!!, compEnv)
                        ensureHashable(k)
                        map[PyKey(k)] = evalExpr(expr.value, compEnv)
                    }
                }
                PyDict(map)
            }
            is Expr.Attribute -> getAttribute(evalExpr(expr.obj, env), expr.name, expr.line)
            is Expr.Subscript -> {
                val obj = evalExpr(expr.obj, env)
                val idx = evalExpr(expr.index, env)
                getSubscript(obj, idx, expr.line)
            }
            is Expr.SliceExpr -> {
                val s = expr.start?.let { evalExpr(it, env).asBigInt()?.toInt() }
                val e = expr.stop?.let { evalExpr(it, env).asBigInt()?.toInt() }
                val st = expr.step?.let { evalExpr(it, env).asBigInt()?.toInt() }
                PySlice(s, e, st)
            }
            is Expr.Call -> {
                val callee = evalExpr(expr.callee, env)
                val posArgs = mutableListOf<PyValue>()
                val kwArgs = linkedMapOf<String, PyValue>()
                for (a in expr.args) {
                    when {
                        a.isStar -> posArgs.addAll(toIterableList(evalExpr(a.value, env)))
                        a.isDoubleStar -> {
                            val m = evalExpr(a.value, env)
                            val entries = when (m) {
                                is PyDict -> m.entries
                                is PyFrozenDict -> m.entries
                                else -> throw PyException("TypeError", "argument after ** must be a mapping", expr.line, activeFileName)
                            }
                            for ((k, v) in entries) kwArgs[k.value.toDisplayString()] = v
                        }
                        a.name != null -> kwArgs[a.name] = evalExpr(a.value, env)
                        else -> posArgs.add(evalExpr(a.value, env))
                    }
                }
                if (callee is PyNativeFunction && (callee.name == "eval" || callee.name == "exec")) {
                    currentEnv = env
                }
                callValue(callee, posArgs, kwArgs, expr.line)
            }
            is Expr.Lambda -> {
                val defaults = mutableMapOf<String, PyValue>()
                for (p in expr.params) {
                    if (p.defaultExpr != null) defaults[p.name] = evalExpr(p.defaultExpr, env)
                }
                PyFunction("<lambda>", expr.params, listOf(Stmt.Return(expr.body, expr.line)), env, defaults = defaults)
            }
            is Expr.Await -> {
                val target = evalExpr(expr.value, env)
                if (target is PyCoroutine) target.awaitResult() else target
            }
        }
    }

    private suspend fun evalCompClauses(
        clauses: List<CompClause>,
        idx: Int,
        env: PyEnvironment,
        onLeaf: suspend (PyEnvironment) -> Unit
    ) {
        if (idx >= clauses.size) {
            onLeaf(env)
            return
        }
        val clause = clauses[idx]
        for (item in toIterableList(evalExpr(clause.iterable, env))) {
            tick()
            assignTarget(clause.target, item, env, 0)
            if (clause.conditions.all { evalExpr(it, env).toBool() }) {
                evalCompClauses(clauses, idx + 1, env, onLeaf)
            }
        }
    }

    suspend fun callValue(
        callee: PyValue,
        args: List<PyValue>,
        kwargs: Map<String, PyValue>,
        line: Int
    ): PyValue = when (callee) {
        is PyNativeFunction -> callee.invoke(this, args, kwargs)
        is PyBoundMethod -> callValue(callee.function, listOf(callee.receiver) + args, kwargs, line)
        is PyFunction -> invokePyFunction(callee, args, kwargs, line)
        is PyClass -> {
            val instance = PyInstance(callee)
            val init = callee.lookupAttribute("__init__")
            if (init != null) {
                callValue(init, listOf(instance) + args, kwargs, line)
            } else if (callee.isSubclassOf(PyClass("BaseException", emptyList())) || callee.name.endsWith("Error")) {
                instance.fields["args"] = PyTuple(args)
            }
            instance
        }
        is PyInstance -> {
            val callAttr = callee.pyClass.lookupAttribute("__call__")
                ?: throw PyException("TypeError", "'${callee.pyClass.name}' object is not callable", line, activeFileName)
            callValue(callAttr, listOf(callee) + args, kwargs, line)
        }
        else -> throw PyException("TypeError", "'${callee.typeName}' object is not callable", line, activeFileName)
    }

    private suspend fun invokePyFunction(
        fn: PyFunction,
        args: List<PyValue>,
        kwargs: Map<String, PyValue>,
        callLine: Int
    ): PyValue {
        val fnEnv = PyEnvironment(parent = fn.closure)
        val remainingKwargs = LinkedHashMap(kwargs)
        var argIdx = 0

        for (param in fn.params) {
            when {
                param.isVarArgs -> {
                    val rest = if (argIdx < args.size) args.subList(argIdx, args.size) else emptyList()
                    argIdx = args.size
                    fnEnv.defineLocal(param.name, PyTuple(rest))
                }
                param.isKwArgs -> {
                    val dict = PyDict()
                    for ((k, v) in remainingKwargs) dict.set(PyStr(k), v)
                    remainingKwargs.clear()
                    fnEnv.defineLocal(param.name, dict)
                }
                else -> {
                    val value = when {
                        argIdx < args.size -> args[argIdx++]
                        remainingKwargs.containsKey(param.name) -> remainingKwargs.remove(param.name)!!
                        fn.defaults.containsKey(param.name) -> fn.defaults[param.name]!!
                        else -> throw PyException(
                            "TypeError",
                            "${fn.name}() missing 1 required positional argument: '${param.name}'",
                            callLine,
                            activeFileName
                        )
                    }
                    fnEnv.defineLocal(param.name, value)
                }
            }
        }

        if (fn.isAsync) {
            return PyCoroutine(fn.name) {
                try {
                    execBlock(fn.body, fnEnv)
                    PyNone
                } catch (r: ReturnSignal) {
                    r.value
                } catch (e: PyException) {
                    e.addFrame(activeFileName, callLine, fn.name)
                    throw e
                }
            }
        }

        if (fn.isGenerator) {
            val yielded = mutableListOf<PyValue>()
            execGeneratorBody(fn.body, fnEnv, yielded)
            return PyIterator(yielded, "generator")
        }

        return try {
            execBlock(fn.body, fnEnv)
            PyNone
        } catch (r: ReturnSignal) {
            r.value
        } catch (e: PyException) {
            e.addFrame(activeFileName, callLine, fn.name)
            throw e
        }
    }

    private suspend fun execGeneratorBody(stmts: List<Stmt>, env: PyEnvironment, out: MutableList<PyValue>) {
        for (s in stmts) {
            when (s) {
                is Stmt.Yield -> out.add(s.value?.let { evalExpr(it, env) } ?: PyNone)
                is Stmt.For -> {
                    for (item in toIterableList(evalExpr(s.iterable, env))) {
                        assignTarget(s.target, item, env, s.line)
                        try { execGeneratorBody(s.body, env, out) }
                        catch (_: BreakSignal) { break }
                        catch (_: ContinueSignal) { continue }
                    }
                }
                is Stmt.While -> {
                    while (evalExpr(s.condition, env).toBool()) {
                        tick()
                        try { execGeneratorBody(s.body, env, out) }
                        catch (_: BreakSignal) { break }
                        catch (_: ContinueSignal) { continue }
                    }
                }
                is Stmt.If -> {
                    var matched = false
                    for ((c, b) in s.branches) {
                        if (evalExpr(c, env).toBool()) {
                            execGeneratorBody(b, env, out)
                            matched = true; break
                        }
                    }
                    if (!matched) execGeneratorBody(s.elseBody, env, out)
                }
                else -> try { execStmt(s, env) } catch (y: YieldSignal) { out.add(y.value) } catch (_: ReturnSignal) { return }
            }
        }
    }

    private fun containsYield(stmts: List<Stmt>): Boolean = stmts.any { s ->
        when (s) {
            is Stmt.Yield -> true
            is Stmt.If -> s.branches.any { containsYield(it.second) } || containsYield(s.elseBody)
            is Stmt.While -> containsYield(s.body) || containsYield(s.elseBody)
            is Stmt.For -> containsYield(s.body) || containsYield(s.elseBody)
            is Stmt.TryStmt -> containsYield(s.body) || s.handlers.any { containsYield(it.body) }
            is Stmt.WithStmt -> containsYield(s.body)
            else -> false
        }
    }

    suspend fun getAttribute(obj: PyValue, name: String, line: Int): PyValue {
        if (name == "__class__") return PyStr("<class '${obj.typeName}'>")
        when (obj) {
            is PyLazyModule -> {
                val resolved = obj.resolve()
                return resolved.members[name]
                    ?: throw PyException("AttributeError", "module '${resolved.name}' has no attribute '$name'", line, activeFileName)
            }
            is PyModule -> {
                return obj.members[name]
                    ?: throw PyException("AttributeError", "module '${obj.name}' has no attribute '$name'", line, activeFileName)
            }
            is PyClass -> {
                val attr = obj.lookupAttribute(name)
                    ?: throw PyException("AttributeError", "type object '${obj.name}' has no attribute '$name'", line, activeFileName)
                return when (attr) {
                    is PyStaticMethod -> attr.function
                    is PyClassMethod -> PyBoundMethod(obj, attr.function)
                    else -> attr
                }
            }
            is PyInstance -> {
                obj.fields[name]?.let { return it }
                val attr = obj.pyClass.lookupAttribute(name)
                    ?: throw PyException("AttributeError", "'${obj.pyClass.name}' object has no attribute '$name'", line, activeFileName)
                return when (attr) {
                    is PyProperty -> callValue(attr.getter, listOf(obj), emptyMap(), line)
                    is PyStaticMethod -> attr.function
                    is PyClassMethod -> PyBoundMethod(obj.pyClass, attr.function)
                    is PyFunction, is PyNativeFunction -> PyBoundMethod(obj, attr)
                    else -> attr
                }
            }
            else -> {
                PyMethods.getBuiltinMethod(obj, name)?.let { return it }
                throw PyException("AttributeError", "'${obj.typeName}' object has no attribute '$name'", line, activeFileName)
            }
        }
    }

    suspend fun setAttribute(obj: PyValue, name: String, value: PyValue, line: Int) {
        when (obj) {
            is PyInstance -> {
                val prop = obj.pyClass.lookupAttribute(name) as? PyProperty
                if (prop?.setter != null) {
                    callValue(prop.setter, listOf(obj, value), emptyMap(), line)
                } else {
                    obj.fields[name] = value
                }
            }
            is PyClass -> obj.attributes[name] = value
            is PyModule -> obj.members[name] = value
            else -> throw PyException("AttributeError", "'${obj.typeName}' object attribute '$name' is read-only", line, activeFileName)
        }
    }

    private suspend fun getSubscript(obj: PyValue, idx: PyValue, line: Int): PyValue = when (obj) {
        is PyList -> {
            if (idx is PySlice) PyList(sliceList(obj.elements, idx).toMutableList())
            else {
                val i = normalizeIndex(idx, obj.elements.size, line)
                obj.elements[i]
            }
        }
        is PyTuple -> {
            if (idx is PySlice) PyTuple(sliceList(obj.elements, idx))
            else {
                val i = normalizeIndex(idx, obj.elements.size, line)
                obj.elements[i]
            }
        }
        is PyStr -> {
            if (idx is PySlice) {
                val chars = obj.value.map { PyStr(it.toString()) }
                PyStr(sliceList(chars, idx).joinToString("") { (it as PyStr).value })
            } else {
                val i = normalizeIndex(idx, obj.value.length, line)
                PyStr(obj.value[i].toString())
            }
        }
        is PyBytes -> {
            if (idx is PySlice) {
                PyBytes(sliceByteArray(obj.data, idx))
            } else {
                val i = normalizeIndex(idx, obj.data.size, line)
                PyInt.of(obj.data[i].toInt() and 0xFF)
            }
        }
        is PyMemoryView -> {
            if (idx is PySlice) {
                val step = idx.step ?: 1
                if (step == 1) {
                    val start = (idx.start?.let { if (it < 0) obj.length + it else it } ?: 0).coerceIn(0, obj.length)
                    val stop = (idx.stop?.let { if (it < 0) obj.length + it else it } ?: obj.length).coerceIn(0, obj.length)
                    obj.slice(start, stop)
                } else {
                    PyBytes(sliceList(obj.toByteArray().toList(), idx).toByteArray())
                }
            } else {
                val i = normalizeIndex(idx, obj.length, line)
                PyInt(obj.byteAt(i))
            }
        }
        is PyDict -> obj.get(idx) ?: throw PyException("KeyError", idx.toReprString(), line, activeFileName)
        is PyFrozenDict -> obj.get(idx) ?: throw PyException("KeyError", idx.toReprString(), line, activeFileName)
        is PyRange -> {
            val list = obj.toList()
            if (idx is PySlice) PyList(sliceList(list, idx).toMutableList())
            else list[normalizeIndex(idx, list.size, line)]
        }
        is PyInstance -> {
            val fn = obj.pyClass.lookupAttribute("__getitem__")
                ?: throw PyException("TypeError", "'${obj.pyClass.name}' object is not subscriptable", line, activeFileName)
            callValue(fn, listOf(obj, idx), emptyMap(), line)
        }
        else -> throw PyException("TypeError", "'${obj.typeName}' object is not subscriptable", line, activeFileName)
    }

    private suspend fun setSubscript(obj: PyValue, idx: PyValue, value: PyValue, line: Int) {
        when (obj) {
            is PyList -> {
                val i = normalizeIndex(idx, obj.elements.size, line)
                obj.elements[i] = value
            }
            is PyDict -> obj.set(idx, value)
            is PyFrozenDict -> throw PyException("TypeError", "'frozendict' object does not support item assignment", line, activeFileName)
            is PyInstance -> {
                val fn = obj.pyClass.lookupAttribute("__setitem__")
                    ?: throw PyException("TypeError", "'${obj.pyClass.name}' object does not support item assignment", line, activeFileName)
                callValue(fn, listOf(obj, idx, value), emptyMap(), line)
            }
            else -> throw PyException("TypeError", "'${obj.typeName}' object does not support item assignment", line, activeFileName)
        }
    }

    private fun <T> sliceList(items: List<T>, slice: PySlice): List<T> {
        val len = items.size
        val step = slice.step ?: 1
        if (step == 0) throw PyException("ValueError", "slice step cannot be zero")
        val out = mutableListOf<T>()
        if (step > 0) {
            val start = (slice.start?.let { if (it < 0) len + it else it } ?: 0).coerceIn(0, len)
            val stop = (slice.stop?.let { if (it < 0) len + it else it } ?: len).coerceIn(0, len)
            var i = start
            while (i < stop) { out.add(items[i]); i += step }
        } else {
            val start = (slice.start?.let { if (it < 0) len + it else it } ?: (len - 1)).coerceIn(-1, len - 1)
            val stop = (slice.stop?.let { if (it < 0) len + it else it } ?: -1).coerceIn(-1, len - 1)
            var i = start
            while (i > stop) { out.add(items[i]); i += step }
        }
        return out
    }

    private fun sliceByteArray(data: ByteArray, slice: PySlice): ByteArray {
        val len = data.size
        val step = slice.step ?: 1
        if (step == 0) throw PyException("ValueError", "slice step cannot be zero")
        if (step == 1) {
            val start = (slice.start?.let { if (it < 0) len + it else it } ?: 0).coerceIn(0, len)
            val stop = (slice.stop?.let { if (it < 0) len + it else it } ?: len).coerceIn(0, len)
            return if (stop <= start) ByteArray(0) else data.copyOfRange(start, stop)
        }
        val out = java.io.ByteArrayOutputStream()
        if (step > 0) {
            val start = (slice.start?.let { if (it < 0) len + it else it } ?: 0).coerceIn(0, len)
            val stop = (slice.stop?.let { if (it < 0) len + it else it } ?: len).coerceIn(0, len)
            var i = start
            while (i < stop) { out.write(data[i].toInt()); i += step }
        } else {
            val start = (slice.start?.let { if (it < 0) len + it else it } ?: (len - 1)).coerceIn(-1, len - 1)
            val stop = (slice.stop?.let { if (it < 0) len + it else it } ?: -1).coerceIn(-1, len - 1)
            var i = start
            while (i > stop) { out.write(data[i].toInt()); i += step }
        }
        return out.toByteArray()
    }

    private fun normalizeIndex(idxVal: PyValue, size: Int, line: Int): Int {
        val raw = idxVal.asBigInt()?.toInt()
            ?: throw PyException("TypeError", "indices must be integers, not '${idxVal.typeName}'", line, activeFileName)
        val norm = if (raw < 0) size + raw else raw
        if (norm !in 0 until size) throw PyException("IndexError", "index $raw out of range", line, activeFileName)
        return norm
    }

    private suspend fun assignTarget(target: AssignTarget, value: PyValue, env: PyEnvironment, line: Int) {
        when (target) {
            is AssignTarget.Name -> env.set(target.name, value)
            is AssignTarget.Attribute -> setAttribute(evalExpr(target.obj, env), target.name, value, line)
            is AssignTarget.Subscript -> setSubscript(evalExpr(target.obj, env), evalExpr(target.index, env), value, line)
            is AssignTarget.Starred -> assignTarget(target.inner, value, env, line)
            is AssignTarget.Sequence -> {
                val items = toIterableList(value)
                val starIdx = target.items.indexOfFirst { it is AssignTarget.Starred }
                if (starIdx < 0) {
                    if (items.size != target.items.size) {
                        throw PyException("ValueError", "not enough/too many values to unpack (expected ${target.items.size}, got ${items.size})", line, activeFileName)
                    }
                    for (i in target.items.indices) assignTarget(target.items[i], items[i], env, line)
                } else {
                    val afterCount = target.items.size - starIdx - 1
                    if (items.size < target.items.size - 1) {
                        throw PyException("ValueError", "not enough values to unpack", line, activeFileName)
                    }
                    for (i in 0 until starIdx) assignTarget(target.items[i], items[i], env, line)
                    val middle = items.subList(starIdx, items.size - afterCount).toMutableList()
                    assignTarget(target.items[starIdx], PyList(middle), env, line)
                    for (j in 0 until afterCount) {
                        assignTarget(target.items[starIdx + 1 + j], items[items.size - afterCount + j], env, line)
                    }
                }
            }
        }
    }

    private suspend fun readTarget(target: AssignTarget, env: PyEnvironment, line: Int): PyValue = when (target) {
        is AssignTarget.Name -> env.get(target.name)
            ?: throw PyException("NameError", "name '${target.name}' is not defined", line, activeFileName)
        is AssignTarget.Attribute -> getAttribute(evalExpr(target.obj, env), target.name, line)
        is AssignTarget.Subscript -> getSubscript(evalExpr(target.obj, env), evalExpr(target.index, env), line)
        else -> throw PyException("SyntaxError", "illegal target for augmented assignment", line, activeFileName)
    }

    private suspend fun deleteTarget(target: AssignTarget, env: PyEnvironment, line: Int) {
        when (target) {
            is AssignTarget.Name -> if (!env.delete(target.name)) throw PyException("NameError", "name '${target.name}' is not defined", line, activeFileName)
            is AssignTarget.Subscript -> {
                val obj = evalExpr(target.obj, env)
                val idx = evalExpr(target.index, env)
                when (obj) {
                    is PyDict -> if (obj.entries.remove(PyKey(idx)) == null) throw PyException("KeyError", idx.toReprString(), line, activeFileName)
                    is PyList -> obj.elements.removeAt(normalizeIndex(idx, obj.elements.size, line))
                    else -> throw PyException("TypeError", "'${obj.typeName}' object does not support item deletion", line, activeFileName)
                }
            }
            else -> Unit
        }
    }

    private fun evalUnary(op: String, v: PyValue, line: Int): PyValue = when (op) {
        "not" -> PyBool.of(!v.toBool())
        "-" -> when (v) {
            is PyInt -> PyInt(v.value.negate())
            is PyBool -> PyInt(v.asBigInt().negate())
            is PyFloat -> PyFloat(-v.value)
            else -> throw PyException("TypeError", "bad operand type for unary -: '${v.typeName}'", line, activeFileName)
        }
        "+" -> when (v) {
            is PyInt, is PyFloat -> v
            is PyBool -> PyInt(v.asBigInt())
            else -> throw PyException("TypeError", "bad operand type for unary +: '${v.typeName}'", line, activeFileName)
        }
        "~" -> {
            val bi = v.asBigInt() ?: throw PyException("TypeError", "bad operand type for unary ~: '${v.typeName}'", line, activeFileName)
            PyInt(bi.not())
        }
        else -> throw PyException("SyntaxError", "unknown unary op '$op'", line, activeFileName)
    }

    suspend fun applyBinaryOp(op: String, l: PyValue, r: PyValue, line: Int): PyValue {
        // Custom dunder methods for instances
        if (l is PyInstance) {
            val dunder = mapOf("+" to "__add__", "-" to "__sub__", "*" to "__mul__", "/" to "__truediv__", "==" to "__eq__")[op]
            if (dunder != null) {
                l.pyClass.lookupAttribute(dunder)?.let { fn ->
                    return callValue(fn, listOf(l, r), emptyMap(), line)
                }
            }
        }

        // Dict / FrozenDict union operator `|` (PEP 584 + PEP 814)
        if (op == "|") {
            if ((l is PyDict || l is PyFrozenDict) && (r is PyDict || r is PyFrozenDict)) {
                val merged = linkedMapOf<PyKey, PyValue>()
                mergeMapping(merged, l, line)
                mergeMapping(merged, r, line)
                return if (l is PyFrozenDict && r is PyFrozenDict) PyFrozenDict(merged) else PyDict(merged)
            }
            if (l is PySet && r is PySet) return PySet(LinkedHashSet(l.elements + r.elements))
        }
        if (op == "&" && l is PySet && r is PySet) return PySet(LinkedHashSet(l.elements.filter { it in r.elements }))
        if (op == "-" && l is PySet && r is PySet) return PySet(LinkedHashSet(l.elements.filter { it !in r.elements }))
        if (op == "^" && l is PySet && r is PySet) {
            val onlyL = l.elements.filter { it !in r.elements }
            val onlyR = r.elements.filter { it !in l.elements }
            return PySet(LinkedHashSet(onlyL + onlyR))
        }

        // String / Bytes / List / Tuple concat & repetition
        if (op == "+") {
            if (l is PyStr && r is PyStr) return PyStr(l.value + r.value)
            if (l is PyBytes && r is PyBytes) return PyBytes(l.data + r.data)
            if (l is PyList && r is PyList) return PyList((l.elements + r.elements).toMutableList())
            if (l is PyTuple && r is PyTuple) return PyTuple(l.elements + r.elements)
        }
        if (op == "*") {
            if (l is PyStr && r.asBigInt() != null) return PyStr(l.value.repeat(r.asBigInt()!!.toInt().coerceAtLeast(0)))
            if (r is PyStr && l.asBigInt() != null) return PyStr(r.value.repeat(l.asBigInt()!!.toInt().coerceAtLeast(0)))
            if (l is PyBytes && r.asBigInt() != null) return PyBytes(repeatBytes(l.data, r.asBigInt()!!.toInt().coerceAtLeast(0)))
            if (r is PyBytes && l.asBigInt() != null) return PyBytes(repeatBytes(r.data, l.asBigInt()!!.toInt().coerceAtLeast(0)))
            if (l is PyList && r.asBigInt() != null) {
                val n = r.asBigInt()!!.toInt().coerceAtLeast(0)
                return PyList(buildList { repeat(n) { addAll(l.elements) } }.toMutableList())
            }
            if (l is PyTuple && r.asBigInt() != null) {
                val n = r.asBigInt()!!.toInt().coerceAtLeast(0)
                return PyTuple(buildList { repeat(n) { addAll(l.elements) } })
            }
        }

        // Fast-path bulk bitwise ops on unsigned little-endian byte-backed PyInts (avoids BigInteger conversion!)
        if (l is PyInt && r is PyInt) {
            val la = l.rawLeBytes
            val rb = r.rawLeBytes
            if (la != null && rb != null) {
                when (op) {
                    "^" -> return PyInt.fromUnsignedLeBytes(PyInt.xorUnsignedLe(la, rb))
                    "&" -> return PyInt.fromUnsignedLeBytes(PyInt.andUnsignedLe(la, rb))
                    "|" -> return PyInt.fromUnsignedLeBytes(PyInt.orUnsignedLe(la, rb))
                }
            }
        }

        // Integer & Float arithmetic
        val li = l.asBigInt()
        val ri = r.asBigInt()
        if (li != null && ri != null && l !is PyFloat && r !is PyFloat) {
            return when (op) {
                "+" -> PyInt(li.add(ri))
                "-" -> PyInt(li.subtract(ri))
                "*" -> PyInt(li.multiply(ri))
                "/" -> {
                    if (ri == BigInteger.ZERO) throw PyException("ZeroDivisionError", "division by zero", line, activeFileName)
                    PyFloat(li.toDouble() / ri.toDouble())
                }
                "//" -> {
                    if (ri == BigInteger.ZERO) throw PyException("ZeroDivisionError", "integer division or modulo by zero", line, activeFileName)
                    PyInt(PyBuiltins.floorDivMod(li, ri).first)
                }
                "%" -> {
                    if (ri == BigInteger.ZERO) throw PyException("ZeroDivisionError", "integer modulo by zero", line, activeFileName)
                    PyInt(PyBuiltins.floorDivMod(li, ri).second)
                }
                "**" -> {
                    if (ri >= BigInteger.ZERO) PyInt(li.pow(ri.toInt()))
                    else PyFloat(li.toDouble().pow(ri.toDouble()))
                }
                "&" -> PyInt(li.and(ri))
                "|" -> PyInt(li.or(ri))
                "^" -> PyInt(li.xor(ri))
                "<<" -> PyInt(li.shiftLeft(ri.toInt()))
                ">>" -> PyInt(li.shiftRight(ri.toInt()))
                else -> throw PyException("TypeError", "unsupported operand type(s) for $op: '${l.typeName}' and '${r.typeName}'", line, activeFileName)
            }
        }

        val ld = l.asDouble()
        val rd = r.asDouble()
        if (ld != null && rd != null) {
            return when (op) {
                "+" -> PyFloat(ld + rd)
                "-" -> PyFloat(ld - rd)
                "*" -> PyFloat(ld * rd)
                "/" -> {
                    if (rd == 0.0) throw PyException("ZeroDivisionError", "float division by zero", line, activeFileName)
                    PyFloat(ld / rd)
                }
                "//" -> {
                    if (rd == 0.0) throw PyException("ZeroDivisionError", "float floor division by zero", line, activeFileName)
                    PyFloat(floor(ld / rd))
                }
                "%" -> {
                    if (rd == 0.0) throw PyException("ZeroDivisionError", "float modulo", line, activeFileName)
                    val q = floor(ld / rd)
                    PyFloat(ld - q * rd)
                }
                "**" -> PyFloat(ld.pow(rd))
                else -> throw PyException("TypeError", "unsupported operand type(s) for $op: '${l.typeName}' and '${r.typeName}'", line, activeFileName)
            }
        }

        throw PyException("TypeError", "unsupported operand type(s) for $op: '${l.typeName}' and '${r.typeName}'", line, activeFileName)
    }

    private suspend fun evalCompareOp(op: String, l: PyValue, r: PyValue, line: Int): Boolean = when (op) {
        "==" -> l.pyEquals(r)
        "!=" -> !l.pyEquals(r)
        "is" -> (l === r) || (l is PyNone && r is PyNone) || (l is PyBool && r is PyBool && l.value == r.value)
        "is not" -> !((l === r) || (l is PyNone && r is PyNone) || (l is PyBool && r is PyBool && l.value == r.value))
        "<" -> compareValues(l, r) < 0
        "<=" -> compareValues(l, r) <= 0
        ">" -> compareValues(l, r) > 0
        ">=" -> compareValues(l, r) >= 0
        "in" -> containsItem(r, l, line)
        "not in" -> !containsItem(r, l, line)
        else -> false
    }

    fun compareValues(a: PyValue, b: PyValue): Int {
        val ai = a.asBigInt()
        val bi = b.asBigInt()
        if (ai != null && bi != null && a !is PyFloat && b !is PyFloat) return ai.compareTo(bi)
        val ad = a.asDouble()
        val bd = b.asDouble()
        if (ad != null && bd != null) return ad.compareTo(bd)
        if (a is PyStr && b is PyStr) return a.value.compareTo(b.value)
        if (a is PyTuple && b is PyTuple) {
            val minLen = minOf(a.elements.size, b.elements.size)
            for (i in 0 until minLen) {
                val c = compareValues(a.elements[i], b.elements[i])
                if (c != 0) return c
            }
            return a.elements.size.compareTo(b.elements.size)
        }
        throw PyException("TypeError", "'<' not supported between instances of '${a.typeName}' and '${b.typeName}'")
    }

    private suspend fun containsItem(container: PyValue, item: PyValue, line: Int): Boolean = when (container) {
        is PyStr -> {
            val sub = (item as? PyStr)?.value
                ?: throw PyException("TypeError", "'in <string>' requires string as left operand", line, activeFileName)
            container.value.contains(sub)
        }
        is PyList -> container.elements.any { it.pyEquals(item) }
        is PyTuple -> container.elements.any { it.pyEquals(item) }
        is PyDict -> container.containsKey(item)
        is PyFrozenDict -> container.containsKey(item)
        is PySet -> container.elements.contains(PyKey(item))
        is PyRange -> item.asBigInt()?.let { container.contains(it.toLong()) } ?: false
        else -> toIterableList(container).any { it.pyEquals(item) }
    }

    suspend fun toIterableList(value: PyValue): List<PyValue> = when (value) {
        is PyList -> value.elements.toList()
        is PyTuple -> value.elements
        is PyRange -> value.toList()
        is PyStr -> value.value.map { PyStr(it.toString()) }
        is PyBytes -> value.data.map { PyInt(it.toInt() and 0xFF) }
        is PyMemoryView -> value.toByteArray().map { PyInt(it.toInt() and 0xFF) }
        is PyDict -> value.entries.keys.map { it.value }
        is PyFrozenDict -> value.entries.keys.map { it.value }
        is PySet -> value.elements.map { it.value }
        is PyIterator -> value.remaining()
        is PyFile -> value.readLines().map { PyStr(it) }
        is PyInstance -> {
            val iterFn = value.pyClass.lookupAttribute("__iter__")
                ?: throw PyException("TypeError", "'${value.pyClass.name}' object is not iterable")
            toIterableList(callValue(iterFn, listOf(value), emptyMap(), 0))
        }
        else -> throw PyException("TypeError", "'${value.typeName}' object is not iterable")
    }

    suspend fun pyLen(v: PyValue): Long = when (v) {
        is PyStr -> v.value.length.toLong()
        is PyBytes -> v.data.size.toLong()
        is PyMemoryView -> v.length.toLong()
        is PyList -> v.elements.size.toLong()
        is PyTuple -> v.elements.size.toLong()
        is PyDict -> v.entries.size.toLong()
        is PyFrozenDict -> v.entries.size.toLong()
        is PySet -> v.elements.size.toLong()
        is PyRange -> v.size
        is PyInstance -> {
            val lenFn = v.pyClass.lookupAttribute("__len__")
                ?: throw PyException("TypeError", "object of type '${v.pyClass.name}' has no len()")
            callValue(lenFn, listOf(v), emptyMap(), 0).asBigInt()?.toLong() ?: 0L
        }
        else -> throw PyException("TypeError", "object of type '${v.typeName}' has no len()")
    }

    private fun repeatBytes(data: ByteArray, times: Int): ByteArray {
        if (times <= 0 || data.isEmpty()) return ByteArray(0)
        val out = ByteArray(data.size * times)
        for (i in 0 until times) {
            System.arraycopy(data, 0, out, i * data.size, data.size)
        }
        return out
    }

    suspend fun pyToString(v: PyValue): String {
        if (v is PyInstance) {
            v.fields["__str__"]?.let { return callValue(it, emptyList(), emptyMap(), 0).toDisplayString() }
            v.pyClass.lookupAttribute("__str__")?.let { return callValue(it, listOf(v), emptyMap(), 0).toDisplayString() }
            v.pyClass.lookupAttribute("__repr__")?.let { return callValue(it, listOf(v), emptyMap(), 0).toDisplayString() }
        }
        return v.toDisplayString()
    }

    suspend fun pyToRepr(v: PyValue): String {
        if (v is PyInstance) {
            v.pyClass.lookupAttribute("__repr__")?.let { return callValue(it, listOf(v), emptyMap(), 0).toDisplayString() }
        }
        return v.toReprString()
    }

    private suspend fun evaluateFString(template: String, env: PyEnvironment, line: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                if (i + 1 < template.length && template[i + 1] == '{') {
                    sb.append('{'); i += 2; continue
                }
                var depth = 1
                var j = i + 1
                while (j < template.length && depth > 0) {
                    if (template[j] == '{') depth++
                    else if (template[j] == '}') depth--
                    if (depth > 0) j++
                }
                val rawField = template.substring(i + 1, j)
                // Split format spec at top-level ':'
                var colonPos = -1
                var pDepth = 0
                for (k in rawField.indices) {
                    when (rawField[k]) {
                        '(', '[', '{' -> pDepth++
                        ')', ']', '}' -> pDepth--
                        ':' -> if (pDepth == 0) { colonPos = k; break }
                    }
                }
                val exprPart = if (colonPos >= 0) rawField.substring(0, colonPos) else rawField
                val specPart = if (colonPos >= 0) rawField.substring(colonPos + 1) else ""
                val debugSuffix = exprPart.trimEnd().endsWith("=")
                val cleanExpr = if (debugSuffix) exprPart.trimEnd().dropLast(1) else exprPart
                val tokens = PyLexer(cleanExpr, activeFileName).tokenize()
                val parsed = PyParser(tokens, activeFileName).parseSingleExpression()
                val evaluated = evalExpr(parsed, env)
                if (debugSuffix) {
                    sb.append(exprPart).append(if (specPart.isNotEmpty()) formatValue(evaluated, specPart) else pyToRepr(evaluated))
                } else {
                    sb.append(formatValue(evaluated, specPart))
                }
                i = j + 1
            } else if (c == '}' && i + 1 < template.length && template[i + 1] == '}') {
                sb.append('}'); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    suspend fun formatValue(value: PyValue, spec: String): String {
        if (spec.isEmpty()) return pyToString(value)
        // Handle float precision like `.2f`, `,`, `,.2f`, `%`
        if (spec.endsWith("f")) {
            val d = value.asDouble() ?: return pyToString(value)
            val prec = spec.substringAfter('.', "6").removeSuffix("f").toIntOrNull() ?: 6
            val comma = spec.contains(',')
            val fmt = if (comma) "%,.${prec}f" else "%.${prec}f"
            return String.format(Locale.US, fmt, d)
        }
        if (spec.endsWith("%")) {
            val d = value.asDouble() ?: return pyToString(value)
            val prec = spec.substringAfter('.', "2").removeSuffix("%").toIntOrNull() ?: 2
            return String.format(Locale.US, "%.${prec}f%%", d * 100.0)
        }
        if (spec == ",") {
            val bi = value.asBigInt()
            if (bi != null) return String.format(Locale.US, "%,d", bi)
        }
        if (spec.endsWith("x") || spec.endsWith("X")) {
            val bi = value.asBigInt() ?: return pyToString(value)
            val s = bi.toString(16)
            return if (spec.endsWith("X")) s.uppercase() else s
        }
        if (spec.endsWith("b")) {
            val bi = value.asBigInt() ?: return pyToString(value)
            return bi.toString(2)
        }
        // Width/alignment e.g. >10, <10, ^10, 05d
        val alignMatch = Regex("^(.?)([<>^])(\\d+)$").matchEntire(spec)
        if (alignMatch != null) {
            val fill = alignMatch.groupValues[1].firstOrNull() ?: ' '
            val align = alignMatch.groupValues[2]
            val width = alignMatch.groupValues[3].toInt()
            val text = pyToString(value)
            if (text.length >= width) return text
            return when (align) {
                ">" -> text.padStart(width, fill)
                "<" -> text.padEnd(width, fill)
                else -> {
                    val left = (width - text.length) / 2
                    val right = width - text.length - left
                    fill.toString().repeat(left) + text + fill.toString().repeat(right)
                }
            }
        }
        return pyToString(value)
    }

    private fun mergeMapping(target: LinkedHashMap<PyKey, PyValue>, mapping: PyValue, line: Int) {
        when (mapping) {
            is PyDict -> target.putAll(mapping.entries)
            is PyFrozenDict -> target.putAll(mapping.entries)
            else -> throw PyException("TypeError", "'${mapping.typeName}' object is not a mapping", line, activeFileName)
        }
    }

    private fun loadModuleOrThrow(name: String, line: Int): PyModule {
        moduleCache[name]?.let { return it }
        val loaded = PyStdLib.loadModule(name, workspaceDir, this)
            ?: throw PyException("ModuleNotFoundError", "No module named '$name'", line, activeFileName)
        moduleCache[name] = loaded
        return loaded
    }

    private fun matchesException(e: PyException, excSpec: PyValue): Boolean = when (excSpec) {
        is PyTuple -> excSpec.elements.any { matchesException(e, it) }
        is PyClass -> {
            if (excSpec.name == "BaseException") true
            else if (excSpec.name == "Exception") e.typeName != "KeyboardInterrupt" && e.typeName != "SystemExit"
            else if (excSpec.name == "ImportError" && e.typeName == "ModuleNotFoundError") true
            else excSpec.name == e.typeName
        }
        else -> false
    }

    private suspend fun matchPattern(
        pattern: MatchPattern,
        subject: PyValue,
        bindings: MutableMap<String, PyValue>,
        env: PyEnvironment
    ): Boolean = when (pattern) {
        is MatchPattern.Wildcard -> true
        is MatchPattern.Capture -> {
            bindings[pattern.name] = subject
            true
        }
        is MatchPattern.Value -> evalExpr(pattern.expr, env).pyEquals(subject)
        is MatchPattern.Or -> pattern.options.any { matchPattern(it, subject, bindings, env) }
        is MatchPattern.Sequence -> {
            val seq = when (subject) {
                is PyList -> subject.elements
                is PyTuple -> subject.elements
                else -> return false
            }
            if (seq.size != pattern.items.size) return false
            pattern.items.indices.all { matchPattern(pattern.items[it], seq[it], bindings, env) }
        }
    }
}
