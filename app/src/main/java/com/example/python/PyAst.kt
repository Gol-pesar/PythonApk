package com.example.python

sealed class Expr {
    abstract val line: Int

    data class Literal(val value: PyValue, override val line: Int) : Expr()
    data class Identifier(val name: String, override val line: Int) : Expr()
    data class FString(val rawTemplate: String, override val line: Int) : Expr()
    data class Unary(val op: String, val operand: Expr, override val line: Int) : Expr()
    data class Binary(val left: Expr, val op: String, val right: Expr, override val line: Int) : Expr()
    data class BoolOp(val op: String, val values: List<Expr>, override val line: Int) : Expr()
    data class Compare(val left: Expr, val ops: List<String>, val comparators: List<Expr>, override val line: Int) : Expr()
    data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int) : Expr()
    data class NamedExpr(val target: String, val value: Expr, override val line: Int) : Expr()
    data class Starred(val value: Expr, override val line: Int) : Expr()
    data class DoubleStarred(val value: Expr, override val line: Int) : Expr()
    data class ListLiteral(val elements: List<Expr>, override val line: Int) : Expr()
    data class TupleLiteral(val elements: List<Expr>, override val line: Int) : Expr()
    data class SetLiteral(val elements: List<Expr>, override val line: Int) : Expr()
    data class DictLiteral(val entries: List<DictEntry>, override val line: Int) : Expr()
    data class ListComp(val element: Expr, val clauses: List<CompClause>, override val line: Int) : Expr()
    data class SetComp(val element: Expr, val clauses: List<CompClause>, override val line: Int) : Expr()
    data class DictComp(val key: Expr?, val value: Expr, val isUnpack: Boolean, val clauses: List<CompClause>, override val line: Int) : Expr()
    data class Attribute(val obj: Expr, val name: String, override val line: Int) : Expr()
    data class Subscript(val obj: Expr, val index: Expr, override val line: Int) : Expr()
    data class SliceExpr(val start: Expr?, val stop: Expr?, val step: Expr?, override val line: Int) : Expr()
    data class Call(val callee: Expr, val args: List<CallArg>, override val line: Int) : Expr()
    data class Lambda(val params: List<PyParam>, val body: Expr, override val line: Int) : Expr()
    data class Await(val value: Expr, override val line: Int) : Expr()
}

data class DictEntry(val key: Expr?, val value: Expr) // key == null means **value unpacking

data class CompClause(
    val target: AssignTarget,
    val iterable: Expr,
    val conditions: List<Expr>
)

data class CallArg(
    val name: String? = null,
    val value: Expr,
    val isStar: Boolean = false,
    val isDoubleStar: Boolean = false
)

data class PyParam(
    val name: String,
    val defaultExpr: Expr? = null,
    val isVarArgs: Boolean = false,
    val isKwArgs: Boolean = false
)

sealed class AssignTarget {
    data class Name(val name: String) : AssignTarget()
    data class Attribute(val obj: Expr, val name: String) : AssignTarget()
    data class Subscript(val obj: Expr, val index: Expr) : AssignTarget()
    data class Sequence(val items: List<AssignTarget>) : AssignTarget()
    data class Starred(val inner: AssignTarget) : AssignTarget()
}

sealed class Stmt {
    abstract val line: Int

    data class ExprStmt(val expr: Expr, override val line: Int) : Stmt()
    data class Assign(val targets: List<AssignTarget>, val value: Expr, override val line: Int) : Stmt()
    data class AugAssign(val target: AssignTarget, val op: String, val value: Expr, override val line: Int) : Stmt()
    data class Pass(override val line: Int) : Stmt()
    data class Break(override val line: Int) : Stmt()
    data class Continue(override val line: Int) : Stmt()
    data class Return(val value: Expr?, override val line: Int) : Stmt()
    data class Yield(val value: Expr?, override val line: Int) : Stmt()
    data class Del(val targets: List<AssignTarget>, override val line: Int) : Stmt()
    data class Assert(val test: Expr, val message: Expr?, override val line: Int) : Stmt()
    data class Raise(val exception: Expr?, override val line: Int) : Stmt()
    data class Global(val names: List<String>, override val line: Int) : Stmt()
    data class Nonlocal(val names: List<String>, override val line: Int) : Stmt()

    data class If(
        val branches: List<Pair<Expr, List<Stmt>>>,
        val elseBody: List<Stmt>,
        override val line: Int
    ) : Stmt()

    data class While(
        val condition: Expr,
        val body: List<Stmt>,
        val elseBody: List<Stmt>,
        override val line: Int
    ) : Stmt()

    data class For(
        val target: AssignTarget,
        val iterable: Expr,
        val body: List<Stmt>,
        val elseBody: List<Stmt>,
        override val line: Int,
        val isAsync: Boolean = false
    ) : Stmt()

    data class FuncDef(
        val name: String,
        val params: List<PyParam>,
        val body: List<Stmt>,
        val decorators: List<Expr>,
        override val line: Int,
        val isAsync: Boolean = false
    ) : Stmt()

    data class ClassDef(
        val name: String,
        val bases: List<Expr>,
        val body: List<Stmt>,
        val decorators: List<Expr>,
        override val line: Int
    ) : Stmt()

    data class TryStmt(
        val body: List<Stmt>,
        val handlers: List<ExceptHandler>,
        val elseBody: List<Stmt>,
        val finallyBody: List<Stmt>,
        override val line: Int
    ) : Stmt()

    data class WithStmt(
        val items: List<Pair<Expr, AssignTarget?>>,
        val body: List<Stmt>,
        override val line: Int,
        val isAsync: Boolean = false
    ) : Stmt()

    data class ImportStmt(
        val names: List<Pair<String, String?>>,
        val isLazy: Boolean = false,
        override val line: Int
    ) : Stmt()

    data class FromImportStmt(
        val module: String,
        val names: List<Pair<String, String?>>, // ("*", null) for star import
        val isLazy: Boolean = false,
        override val line: Int
    ) : Stmt()

    data class MatchStmt(
        val subject: Expr,
        val cases: List<MatchCase>,
        override val line: Int
    ) : Stmt()
}

data class ExceptHandler(
    val exceptionType: Expr?,
    val alias: String?,
    val body: List<Stmt>,
    val line: Int
)

data class MatchCase(
    val pattern: MatchPattern,
    val guard: Expr?,
    val body: List<Stmt>,
    val line: Int
)

sealed class MatchPattern {
    data object Wildcard : MatchPattern()
    data class Value(val expr: Expr) : MatchPattern()
    data class Capture(val name: String) : MatchPattern()
    data class Or(val options: List<MatchPattern>) : MatchPattern()
    data class Sequence(val items: List<MatchPattern>) : MatchPattern()
}
