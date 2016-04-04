package withController.`yield`

import withController.api.Continuation
import withController.api.Coroutine

fun main(args: Array<String>) {
    println(generate(Machine()).joinToString())

    val sequence = generate(Machine())
    println(sequence.zip(sequence).joinToString())
}

fun <T> generate(c: () -> Coroutine<
        SequenceGeneratorController,
        SequenceGeneratorIterator<T>
        >): Sequence<T> = SequenceGenerator(c)

/*
    generate {
        println("yield(1)")
        yield(1)
        println("yield(2)")
        yield(2)
        println("done")
    }
 */
class Machine() : Coroutine<SequenceGeneratorController, SequenceGeneratorIterator<Int>>,
        Continuation<Unit, SequenceGeneratorIterator<Int>>,
        Function0<Coroutine<SequenceGeneratorController, SequenceGeneratorIterator<Int>>> {

    override fun resume(parameter: Unit) {
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
            _rv as Unit
            println("yield(1)")
            label = 1
            controller.yieldValue(1, this)
        }
        1 -> {
            if (_throwable != null) throw _throwable!!
            _rv as Unit
            println("yield(2)")
            label = 2
            controller.yieldValue(2, this)
        }
        2 -> {
            if (_throwable != null) throw _throwable!!
            _rv as Unit
            println("done")
            label = -1
            controller.complete(this)
        }
        else -> throw UnsupportedOperationException("Coroutine $this is in an invalid state")
    }

    private val controller: SequenceGeneratorController get() =
    _controller ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    override val context: SequenceGeneratorIterator<Int> get() =
    _context ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    private var _controller: SequenceGeneratorController? = null
    private var _context: SequenceGeneratorIterator<Int>? = null
    private var _throwable: Throwable? = null
    private var _rv: Any? = null

    private fun createMachine(): Machine = if (_controller == null) this else Machine()
    override fun invoke(): Coroutine<SequenceGeneratorController, SequenceGeneratorIterator<Int>> = createMachine()
    override fun create(controller: SequenceGeneratorController, context: SequenceGeneratorIterator<Int>): Continuation<Unit, SequenceGeneratorIterator<Int>> {
        return createMachine().apply {
            _controller = controller
            _context = context
        }
    }

    override fun toString(): String {
        return "${Machine::_controller.name} coroutine"
    }
}

class SequenceGeneratorIterator<T>() : AbstractIterator<T>() {
    override fun computeNext() {
        nextStep.resume(Unit)
    }

    fun advance(value: T, continuation: Continuation<Unit, SequenceGeneratorIterator<T>>) {
        setNext(value)
        setNextStep(continuation)
    }

    fun complete() = done()

    private lateinit var nextStep: Continuation<Unit, SequenceGeneratorIterator<T>>

    fun setNextStep(step: Continuation<Unit, SequenceGeneratorIterator<T>>) {
        this.nextStep = step
    }
}

class SequenceGenerator<T>(val coroutine: () -> Coroutine<SequenceGeneratorController, SequenceGeneratorIterator<T>>) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val iterator = SequenceGeneratorIterator<T>()
        iterator.setNextStep(coroutine().create(SequenceGeneratorController, iterator))
        return iterator
    }
}

object SequenceGeneratorController {
    fun <T> yieldValue(value: T, machine: Continuation<Unit, SequenceGeneratorIterator<T>>) {
        machine.context.advance(value, machine)
    }

    fun <T> complete(machine: Continuation<Nothing, SequenceGeneratorIterator<T>>) {
        machine.context.complete()
    }
}