package noController.asyncWithParam

import noController.api.Continuation
import noController.api.Coroutine
import java.util.concurrent.CompletableFuture

// TEST CODE

fun main(args: Array<String>) {
    val future = async(1, __anonymous__())
    future.whenComplete { value, t ->
        println("completed with $value")
    }
    future.join()
}


private fun foo(v: Int): CompletableFuture<String> = CompletableFuture.supplyAsync { "foo with $v" }
private fun bar(v: String): CompletableFuture<String> = CompletableFuture.supplyAsync { "bar with $v" }

// LIBRARY CODE

fun <T> async(value: Int, c: (Int) -> Coroutine<CompletableFutureContext<T>>): CompletableFuture<T> = CompletableFutureContext<T>().apply {
    c(value).entryPoint(this).resume(Unit)
}

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
class __anonymous__() : Coroutine<CompletableFutureContext<String>>,
        Continuation<Any?>,
        Function1<Int, Coroutine<CompletableFutureContext<String>>> {

    private fun createMachine(): __anonymous__ = if (_context == null) this else __anonymous__()
    override fun invoke(p1: Int): Coroutine<CompletableFutureContext<String>> = createMachine().apply {
        v = p1
    }

    override fun entryPoint(controller: CompletableFutureContext<String>): Continuation<Unit> {
        return createMachine().apply {
            _context = controller
        }
    }

    override fun resume(data: Any?) {
        _rv = data
        machine()
    }

    override fun resumeWithException(exception: Throwable) {
        _throwable = exception
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
        return "${__anonymous__::_context.name} coroutine"
    }
}

class CompletableFutureContext<T> : CompletableFuture<T>() {
    fun <V> await(future: CompletableFuture<V>, machine: Continuation<V>) {
        future.whenComplete { value, throwable ->
            if (throwable == null)
                machine.resume(value)
            else
                machine.resumeWithException(throwable)
        }
    }

    fun complete(value: T, machine: Continuation<Nothing>) {
        complete(value)
    }

    fun unhandledException(t: Throwable, machine: Continuation<Nothing>) {
        completeExceptionally(t)
    }
}