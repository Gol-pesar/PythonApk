package com.example.python

class PyEnvironment(
    val parent: PyEnvironment? = null,
    val isModuleRoot: Boolean = false
) {
    val values: MutableMap<String, PyValue> = linkedMapOf()
    private val globalNames: MutableSet<String> = mutableSetOf()
    private val nonlocalNames: MutableSet<String> = mutableSetOf()

    fun declareGlobal(name: String) {
        globalNames.add(name)
    }

    fun declareNonlocal(name: String) {
        nonlocalNames.add(name)
    }

    fun root(): PyEnvironment {
        var cur = this
        while (cur.parent != null && !cur.isModuleRoot) {
            cur = cur.parent!!
        }
        return cur
    }

    fun get(name: String): PyValue? {
        if (name in globalNames) return root().values[name]
        values[name]?.let { return it }
        return parent?.get(name)
    }

    fun defineLocal(name: String, value: PyValue) {
        values[name] = value
    }

    fun set(name: String, value: PyValue) {
        if (name in globalNames) {
            root().values[name] = value
            return
        }
        if (name in nonlocalNames) {
            var env = parent
            while (env != null && !env.isModuleRoot) {
                if (env.values.containsKey(name)) {
                    env.values[name] = value
                    return
                }
                env = env.parent
            }
            parent?.values?.set(name, value)
            return
        }
        values[name] = value
    }

    fun delete(name: String): Boolean {
        if (name in globalNames) {
            return root().values.remove(name) != null
        }
        return values.remove(name) != null
    }
}
