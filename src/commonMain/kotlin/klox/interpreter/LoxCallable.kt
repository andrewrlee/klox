package klox.interpreter

import klox.ast.Stmt
import klox.interpreter.Interpreter.RuntimeError

interface LoxCallable {
    fun call(interpreter: Interpreter, args: List<Any?>): Any?
    fun arity(): Int
}

class Return(val value: Any?) : RuntimeException(/* null, null, false, false */)

class LoxFunction(
    private val declaration: Stmt.Function,
    private val closure: Environment,
    private val isInitializer: Boolean = false
) : LoxCallable {

    override fun call(interpreter: Interpreter, args: List<Any?>): Any? {
        val environment = Environment(closure)
        declaration.params.zip(args).forEach { (param, arg) -> environment.define(param.lexeme, arg) }
        try {
            interpreter.executeBlock(declaration.body, environment)
        } catch (ret: Return) {
            return if (isInitializer) closure.getAt(0, "this") else ret.value
        }
        return if (isInitializer) return closure.getAt(0, "this") else null
    }

    override fun arity() = declaration.params.size

    fun bind(instance: LoxInstance): LoxFunction {
        val environment = Environment(closure)
        environment.define("this", instance)
        return LoxFunction(declaration, environment, isInitializer)
    }

    override fun toString() = "<fn ${declaration.name.lexeme}>"
}

class LoxClass(val name: String, private val superclass: LoxClass?, private val methods: Map<String, LoxFunction>) :
    LoxCallable {
    override fun call(interpreter: Interpreter, args: List<Any?>): Any {
        val instance = LoxInstance(this)

        findMethod("init")?.bind(instance)?.call(interpreter, args)

        return instance
    }

    fun findMethod(name: String): LoxFunction? {
        if (this.methods.containsKey(name)) {
            return this.methods[name]
        }
        return this.superclass?.findMethod(name)
    }

    override fun arity() = findMethod("init")?.arity() ?: 0

    override fun toString() = name
}

class LoxInstance(private val loxClass: LoxClass) {
    private val fields = mutableMapOf<String, Any?>()
    override fun toString() = "${loxClass.name} instance"

    operator fun get(key: Token): Any? {
        if (fields.containsKey(key.lexeme)) {
            return fields[key.lexeme]
        }
        loxClass.findMethod(key.lexeme)?.let { return it.bind(this) }
        throw RuntimeError(key, "Undefined property '${key.lexeme}'.")
    }

    operator fun set(name: Token, value: Any?) {
        fields[name.lexeme] = value
    }
}