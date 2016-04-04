package withController.async

import withController.api.Continuation
import withController.api.Coroutine
import java.util.concurrent.CompletableFuture

fun main(args: Array<String>) {
    val future = async(1, Machine())
    future.whenComplete { value, t ->
        println("completed with $value")
    }
    future.join()
}

fun <T> async(value: Int, c: (Int) -> Coroutine<
        AsyncController,
        CompletableFuture<T>
        >): CompletableFuture<T> = CompletableFuture<T>().apply {
    c(value).create(AsyncController, this).resume(Unit)
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
class Machine() : Coroutine<AsyncController, CompletableFuture<String>>,
        Continuation<Any?, CompletableFuture<String>>,
        Function1<Int, Coroutine<AsyncController, CompletableFuture<String>>> {

    private fun createMachine(): Machine = if (_controller == null) this else Machine()
    override fun invoke(p1: Int): Coroutine<AsyncController, CompletableFuture<String>> = createMachine().apply {
        v = p1
    }

    override fun create(controller: AsyncController, context: CompletableFuture<String>): Continuation<Unit, CompletableFuture<String>> {
        return createMachine().apply {
            _controller = controller
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
    private fun machine(): Unit = when (label) {
        0 -> {
            if (_throwable != null) throw _throwable!!
            println("got $v")
            label = 1
            controller.await(foo(v), this)
        }
        1 -> {
            if (_throwable != null) throw _throwable!!
            x = _rv as String
            println("got $x")
            label = 2
            controller.await(bar(x), this)
        }
        2 -> {
            if (_throwable != null) throw _throwable!!
            y = _rv as String
            println("got $y")
            label = -1
            controller.complete(y, this)
        }
        else -> throw UnsupportedOperationException("Coroutine $this is in an invalid state")
    }


    private val controller: AsyncController get() =
    _controller ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    override val context: CompletableFuture<String> get() =
    _context ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    private var _controller: AsyncController? = null
    private var _context: CompletableFuture<String>? = null
    private var _throwable: Throwable? = null
    private var _rv: Any? = null

    private var v: Int = 0 // lateinit not supported
    private lateinit var x: String
    private lateinit var y: String

    override fun toString(): String {
        return "${Machine::_controller.name} coroutine"
    }
}

object AsyncController {
    fun <T> await(future: CompletableFuture<T>, machine: Continuation<T, CompletableFuture<String>>) {
        future.whenComplete { value, throwable ->
            if (throwable == null)
                machine.resume(value)
            else
                machine.resumeAndThrow(throwable)
        }
    }

    fun <T> complete(value: T, machine: Continuation<Nothing, CompletableFuture<T>>) {
        machine.context.complete(value)
    }
}