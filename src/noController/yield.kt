package noController.`yield`

import noController.api.Continuation
import noController.api.Coroutine

fun main(args: Array<String>) {
    println(generate(Machine()).joinToString())

    val sequence = generate(Machine())
    println(sequence.zip(sequence).joinToString())
}

fun <T> generate(c: () -> Coroutine<SequenceGeneratorContext<T>>): Sequence<T> = SequenceGenerator(c)

/*
    generate {
        println("yield(1)")
        yield(1)
        println("yield(2)")
        yield(2)
        println("done")
    }
 */
class Machine() : Coroutine<SequenceGeneratorContext<Int>>,
        Continuation<Unit, SequenceGeneratorContext<Int>>,
        Function0<Coroutine<SequenceGeneratorContext<Int>>> {

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
            context.yieldValue(1, this)
        }
        1 -> {
            if (_throwable != null) throw _throwable!!
            _rv as Unit
            println("yield(2)")
            label = 2
            context.yieldValue(2, this)
        }
        2 -> {
            if (_throwable != null) throw _throwable!!
            _rv as Unit
            println("done")
            label = -1
            context.complete(Unit, this)
        }
        else -> throw UnsupportedOperationException("Coroutine $this is in an invalid state")
    }

    override val context: SequenceGeneratorContext<Int> get() =
    _context ?: throw UnsupportedOperationException("Coroutine $this should be initialized before use")

    private var _context: SequenceGeneratorContext<Int>? = null
    private var _throwable: Throwable? = null
    private var _rv: Any? = null

    private fun createMachine(): Machine = if (_context == null) this else Machine()
    override fun invoke(): Coroutine<SequenceGeneratorContext<Int>> = createMachine()
    override fun create(context: SequenceGeneratorContext<Int>): Continuation<Unit, SequenceGeneratorContext<Int>> {
        return createMachine().apply {
            _context = context
        }
    }

    override fun toString(): String {
        return "${Machine::_context.name} coroutine"
    }
}

class SequenceGeneratorContext<T>() : AbstractIterator<T>() {
    override fun computeNext() {
        nextStep.resume(Unit)
    }

    fun advance(value: T, continuation: Continuation<Unit, SequenceGeneratorContext<T>>) {
        setNext(value)
        setNextStep(continuation)
    }

    fun complete() = done()

    private lateinit var nextStep: Continuation<Unit, SequenceGeneratorContext<T>>

    fun setNextStep(step: Continuation<Unit, SequenceGeneratorContext<T>>) {
        this.nextStep = step
    }

    fun yieldValue(value: T, machine: Continuation<Unit, SequenceGeneratorContext<T>>) {
        advance(value, machine)
    }

    fun complete(u: Unit, machine: Continuation<Nothing, SequenceGeneratorContext<T>>) {
        complete()
    }
}

class SequenceGenerator<T>(val coroutine: () -> Coroutine<SequenceGeneratorContext<T>>) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val iterator = SequenceGeneratorContext<T>()
        iterator.setNextStep(coroutine().create(iterator))
        return iterator
    }
}

