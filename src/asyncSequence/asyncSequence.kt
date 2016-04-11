
import GeneratorController.State
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// NOTE: Stack<T> is sometimes used below as a replacement for Option<T>, i.e. a storage that can be empty or have 1 value
// An implementation may be expected to optimize the stack away to avoid additional allocations

// ===  Library interfaces and functions ===

// === General ===

// `Controller` - controller class
interface Coroutine<Controller> {
    fun create(controller: Controller): Continuation<Unit>
}

interface Continuation<Argument> {
    fun run(value : Argument)
}

// Annotates a parameter whose corresponding argument shall be interpreted as a coroutine
annotation class cofun

// Annotates a method whose invocation constitutes a suspension point in a coroutine
annotation class suspension


// === Async ===

// The type of the factory parameter (`AsyncController<Result>` in this case) is used to resolve
// yielding invocations in the coroutine.
// `Result` is the return type of the coroutine
// The compiler provides a lambda of the form `{ AsyncStateMachine(it) }` to the `coroutine` parameter
fun <Result> async(
        @cofun coroutine : () -> Coroutine<AsyncController<Result>>)
        : CompletableFuture<Result> {
    val controller = AsyncController<Result>()
    controller.machine = coroutine()

    return CompletableFuture.supplyAsync {
        controller.start()
        controller.result
    }
}

// a controller for an async coroutine
class AsyncController<Result> {
    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<AsyncController<Result>>

    fun start() {
        machine.create(this).run(Unit);
    }
    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // TODO: handle exceptions
    // This method is available in an asynchronous coroutine
    @suspension
    fun <Result> await(future : CompletableFuture<Result>, continuation : Continuation<Result>) : Unit {
        future.whenComplete { result, exception ->
            continuation.run(result)
        }
    }

    val maybeResult = Stack<Result>()

    // Every `return` expression in the coroutine corresponds to an invocation of this method
    fun returnResult(result : Result) : Unit {
        assert(maybeResult.empty())
        maybeResult.push(result)
    }

    val result : Result get() {
        if(maybeResult.empty()) throw IllegalStateException("The coroutine is not yet completed")
        return maybeResult.peek()
    }
}




// === Generators ===

// A new instance of a state machine has to be created on each invocation of `iterator()`,
// so it is passed as a factory to the `generate` method.
// The type of the factory parameter (`GeneratorController<Element>` in this case is used to resolve
// yielding invocations in the coroutine.
fun <Element> generate(
        @cofun coroutine: () -> Coroutine<GeneratorController<Element>>) =
        object : Sequence<Element> {
            override fun iterator(): Iterator<Element> {
                val controller = GeneratorController<Element>()
                controller.machine = coroutine()
                return IteratorTask(controller)
            }
        }

// a controller for a generator coroutine
// `Element` is the yield type of the generator
class GeneratorController<Element> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<GeneratorController<Element>>

    var state = State.READY

    private val maybeValue = Stack<Element>()

    private var maybeContinuation = Stack<Continuation<Unit>>()

    // This method is available in a generator in a yielding invocation
    // The identifier `yield` is currently reserved and requires escaping
    // An argument to the parameter `continuation` is provided by the compiler
    @suspension
    fun `yield`(element: Element, continuation : Continuation<Unit>) : Unit {
        assert(state == State.RUNNING)
        maybeValue.push(element)
        maybeContinuation.push(continuation)
        state = State.HAS_VALUE
    }

    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    fun returnResult(result : Unit) : Unit {
        state = State.STOPPED
    }

    fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        machine.create(this).run(Unit);
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = maybeContinuation.pop()
        continuation.run(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun fetchValue() : Element {
        assert(state == State.HAS_VALUE)
        assert(maybeValue.size == 1)
        state = State.READY
        return maybeValue.pop()
    }

    fun dropValue() : Unit {
        fetchValue() // and ignore the result
    }
}

// NOTE: There is no counterpart for this class in async coroutines, because they use standard `CompletableFuture<V>` class
// CONSIDER: possibly the task can be  merged with the controller to reduce allocations?
class IteratorTask<T>(val controller: GeneratorController<T>) : Iterator<T> {
    override fun hasNext() : Boolean {
        when(controller.state) {
            State.INITIAL -> {
                controller.start()
                return controller.state == State.HAS_VALUE
            }

            State.READY -> {
                controller.step()
                assert(controller.state == State.HAS_VALUE || controller.state == State.STOPPED)
                return controller.state == State.HAS_VALUE
            }

            State.HAS_VALUE -> {
                controller.dropValue()
                return hasNext()
            }

            State.STOPPED -> return false

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }

    override fun next(): T {
        when(controller.state) {
            State.INITIAL,
            State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext()
                return next()
            }

            State.HAS_VALUE -> return controller.fetchValue()

            State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }
}

// === Async generators ===
interface AsyncIterator<T> {
    fun hasNext() : CompletableFuture<Boolean>
    fun next() : T
}

interface AsyncSequence<T> {
    fun iterator() : AsyncIterator<T>
}

fun <Element> asyncGenerate(
        @cofun coroutine: () -> Coroutine<AsyncGeneratorController<Element>>) =
        object : AsyncSequence<Element> {
            override fun iterator(): AsyncIterator<Element> {
                val controller = AsyncGeneratorController<Element>()
                controller.machine = coroutine()
                return AsyncIteratorTask(controller)
            }
        }

// a controller for an async generator coroutine
// this controller provides both `yield` and `await` suspension points
// `Element` is the yield type of the generator
class AsyncGeneratorController<Element> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<AsyncGeneratorController<Element>>

    var state = State.READY

    private val maybeValue = Stack<Element>()

    private var maybeContinuation = Stack<Continuation<Unit>>()

    // This method is available in a generator in a yielding invocation
    // An argument to the parameter `continuation` is provided by the compiler
    @suspension
    fun `yield`(element: Element, continuation : Continuation<Unit>) : Unit {
        assert(state == State.RUNNING)
        maybeValue.push(element)
        maybeContinuation.push(continuation)
        state = State.HAS_VALUE
    }

    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // TODO: handle exceptions
    // This method is available in an asynchronous coroutine
    @suspension
    fun <Result> await(future : CompletableFuture<Result>, continuation : Continuation<Result>) : Unit {
        future.whenComplete { result, exception ->
            continuation.run(result)
        }
    }
    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    fun returnResult(result : Unit) : Unit {
        state = State.STOPPED
    }

    fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        machine.create(this).run(Unit);
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = maybeContinuation.pop()
        continuation.run(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun fetchValue() : Element {
        assert(state == State.HAS_VALUE)
        assert(maybeValue.size == 1)
        state = State.READY
        return maybeValue.pop()
    }

    fun dropValue() : Unit {
        fetchValue() // and ignore the result
    }
}

class AsyncIteratorTask<T>(val controller: AsyncGeneratorController<T>) : AsyncIterator<T> {
    override fun hasNext() : CompletableFuture<Boolean> {
        when(controller.state) {
            AsyncGeneratorController.State.INITIAL -> {
                return CompletableFuture.supplyAsync {
                    controller.start()
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.READY -> {
                return CompletableFuture.supplyAsync {
                    controller.step()
                    assert(controller.state == AsyncGeneratorController.State.HAS_VALUE ||
                            controller.state == AsyncGeneratorController.State.STOPPED)
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.HAS_VALUE -> {
                controller.dropValue()
                return hasNext()
            }

            AsyncGeneratorController.State.STOPPED -> return CompletableFuture.completedFuture(false)

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }

    override fun next(): T {
        when(controller.state) {
            AsyncGeneratorController.State.INITIAL,
            AsyncGeneratorController.State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext().get() // `get()` means wait for completion
                return next()
            }

            AsyncGeneratorController.State.HAS_VALUE -> return controller.fetchValue()

            AsyncGeneratorController.State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }
}

// === Examples of usage ===

fun main(args: Array<String>) {
    val a = CompletableFuture.completedFuture("Hello")
    val b = CompletableFuture.completedFuture(42)
    run(a, b)
}

/*
// The source definition of `run`
fun run(a : CompletableFuture<Int>, b : CompletableFuture<Int>) {
    // `async` is defined in the standard library and provides a controller for async coroutines
    // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    val future : Future<String> = async {
        println("start")
        val s = await(a)
        println(s)
        val n = await(b)
        println(n)
        return@async "done"
    }

    val result = future.get(10, TimeUnit.SECONDS)
    println(result)

    var n = 0

    // `generate` is defined in the standard library and provides a controller for generator coroutines
    // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    val sequence = generate {
        println("start")
        `yield`(n++)
        println("between")
        `yield`(n)
        println("after")
        // No value is returned by a generator, the following is generated automatically
        // return@generate Unit
    }

    for (value in sequence) {
        println(value)
    }
}
*/

// The compiler-generated transformation of `run`
fun run(a : CompletableFuture<String>, b : CompletableFuture<Int>) {
    val future : Future<String> = `async` {
        AsyncStateMachine().apply { closure = AsyncClosure(a = a, b = b) }
    }

    val result = future.get(10, TimeUnit.SECONDS)
    println(result)

    val sequence = generate<Int> {
        GeneratorStateMachine().apply { closure = GeneratorClosure(n = 0) }
    }

    val tmpIterator = sequence.iterator()
    while (tmpIterator.hasNext()) {
        val value = tmpIterator.next();
        println(value)
    }
}

// compiler-generated closure
// its name is not essential and is not visible to the user
data class AsyncClosure(val a : CompletableFuture<String>, val b : CompletableFuture<Int>)


// A compiler-generated state machine
class AsyncStateMachine() : Coroutine<AsyncController<String>>, Continuation<Any?> {
    // initialized by the compiler upon creation of the state machine
    lateinit var closure : AsyncClosure
    lateinit var controller : AsyncController<String>

    private var state = 0

    override fun create(controller: AsyncController<String>): Continuation<Unit> {
        this.controller = controller
        return this as Continuation<Unit>
    }

    // compiler-generated from the coroutine body
    // the parameter `continuationParameter` represents the result of the last `await` expression and is cast to an appropriate type in each step
    override fun run(continuationParameter: Any?) {
        // Original source:
        // ```
        // println("start")
        // val s = await(a)
        // println(s)
        // val n = await(b)
        // println(n)
        // return@async "done"
        // ```
        when(this.state) {
        // The step before the first `await`
            0 -> {
                // `continuationParameter` is ignored for the first step, because the coroutine is parameterless
                println("start")
                this.state = 1
                controller.await(this.closure.a, this as Continuation<String>) // a deliberate unchecked cast in compiler-generated code
                return
            }

        // The step between the first `await` and the second `await`
            1 -> {
                val s = continuationParameter as String
                println(s)
                this.state = 2
                controller.await(this.closure.b, this as Continuation<Int>)
                return
            }

        // The step after the second `await`
            2 -> {
                val n = continuationParameter as Int
                println(n)
                this.state = -1
                controller.returnResult("done")
                return
            }

            -1 -> throw IllegalStateException("The state machine has already stopped")

            else -> {
                throw IllegalStateException("Unexpected state $state")
            }

        }
    }
}

// compiler-generated closure
// its name is not essential and is not visible to the user
data class GeneratorClosure(var n : Int)




// The source definition of `run`
fun run() {
    // captured in the closure, shared between all instances of a state machine
    var n = 0

    for (iteration in 1..2) {
        // `generate` is defined in the standard library and provides a controller for generator coroutines
        // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    }
}

// A compiler-generated state machine
class GeneratorStateMachine(
) : Coroutine<GeneratorController<Int>>, Continuation<Any?> {
    // initialized by the compiler upon creation of the state machine
    lateinit var closure : GeneratorClosure

    lateinit var controller : GeneratorController<Int>

    private var state = 0

    override fun create(controller: GeneratorController<Int>): Continuation<Unit> {
        this.controller = controller
        return this as Continuation<Unit>
    }

    // compiler-generated from the coroutine body
    // the parameter `continuationParameter` represents the result of the last `yield` expression
    // and is always `Unit` in this type of coroutines
    override fun run(continuationParameter: Any?) {
        // Original code:
        // ```
        // println("start")
        // `yield`(n++)
        // println("between")
        // `yield`(n)
        // println("after")
        // return@generate Unit
        // ```
        when(this.state) {
            0 -> {
                println("start")
                this.state = 1
                controller.`yield`(closure.n++, this as Continuation<Unit>)
                return;
            }

            1 -> {
                println("between")
                this.state = 2
                controller.`yield`(closure.n, this as Continuation<Unit>)
                return;

            }

            2 -> {
                println("after")
                this.state = -1
                controller.returnResult(Unit)
                return
            }

            -1 -> throw IllegalStateException("The state machine has already stopped")

            else -> {
                throw IllegalStateException("Unexpected state $state")
            }

        }
    }
}