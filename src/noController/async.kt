package noController.async

import noController.api.Continuation
import noController.api.Coroutine
import java.util.concurrent.CompletableFuture

fun main(args: Array<String>) {
    val future = async(1, Machine())
    future.whenComplete { value, t ->
        println("completed with $value")
    }
    future.join()
}

fun <T> async(value: Int, c: (Int) -> Coroutine<CompletableFutureContext<T>>): CompletableFuture<T> = CompletableFutureContext<T>().apply {
    c(value).create(this).resume(Unit)
}

private fun foo(v: Int): CompletableFuture<String> = CompletableFuture.supplyAsync { "foo with $v" }
private fun bar(v: String): CompletableFuture<String> = CompletableFuture.supplyAsync { "bar with $v" }

/*
    async { v ->
        println("got $v")
        val x = await(foo(v))
        println("got $x")
        val y = await(bar(y))
        println("got $y")
        y
    }
 */
class Machine() : Coroutine<CompletableFutureContext<String>>,
        Continuation<Any?>,
        Function1<Int, Coroutine<CompletableFutureContext<String>>> {

    private fun createMachine(): Machine = if (_context == null) this else Machine()
    override fun invoke(p1: Int): Coroutine<CompletableFutureContext<String>> = createMachine().apply {
        v = p1
    }

    override fun create(context: CompletableFutureContext<String>): Continuation<Unit> {
        return createMachine().apply {
            _context = context
        }
    }

    override fun resume(parameter: Any?) {
        _rv = parameter
        machine()
    }

    override fun resumeAndThrow(throwable: Throwable) {
        _throwable = throwable
        machine()
    }

    private var label = 0
    private fun machine() {
        try {
            when (label) {
                    0 -> {
                        if (_throwable != null) throw _throwable!!
                        println("got $v")
                        label = 1
                        context.await(foo(v), this)
                    }
                    1 -> {
                        if (_throwable != null) throw _throwable!!
                        x = _rv as String
                        println("got $x")
                        label = 2
                        context.await(bar(x), this)
                    }
                    2 -> {
                        if (_throwable != null) throw _throwable!!
                        y = _rv as String
                        println("got $y")
                        label = -1
                        context.complete(y, this)
                    }
                    else -> throw UnsupportedOperationException("Coroutine $this is in an invalid state")
                }
        } catch(e: Throwable) {
            context.unhandledException(e, this)
        }
    }


    val context: CompletableFutureContext<String> get() =
    _context ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    private var _context: CompletableFutureContext<String>? = null
    private var _throwable: Throwable? = null
    private var _rv: Any? = null

    private var v: Int = 0 // lateinit not supported
    private lateinit var x: String
    private lateinit var y: String

    override fun toString(): String {
        return "${Machine::_context.name} coroutine"
    }
}

class CompletableFutureContext<T> : CompletableFuture<T>() {
    fun <V> await(future: CompletableFuture<V>, machine: Continuation<V>) {
        future.whenComplete { value, throwable ->
            if (throwable == null)
                machine.resume(value)
            else
                machine.resumeAndThrow(throwable)
        }
    }

    fun complete(value: T, machine: Continuation<Nothing>) {
        complete(value)
    }

    fun unhandledException(t: Throwable, machine: Continuation<Nothing>) {
        completeExceptionally(t)
    }
}