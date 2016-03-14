package proto.async.future

import java.util.concurrent.*

interface CofunInit<T> {
    fun start()
    fun registerResultHandler(handler: (T) -> Unit)
    // exception handler?
}

interface CofunStep<P> {
    fun doStep(parameter: P)
    fun doStepWithException(e: Throwable)
}

// --------

class ResultBox<P>(private val cofun: CofunBase<*>) : CofunStep<P> {

    sealed class Result<out P> {
        class Value<P>(val value: P) : Result<P>()
        class Exception(val exception: Throwable) : Result<Nothing>()
    }

    var result: Result<P>? = null

    override fun doStep(parameter: P) {
        result = Result.Value(parameter)
        cofun.nextStep()
    }

    override fun doStepWithException(e: Throwable) {
        result = Result.Exception(e)
        cofun.nextStep()
    }

}

class FutureWFM(val e: ExecutorService) {
    fun <T> cofun(init: CofunInit<T>): Future<T> {
        var value: T? = null
        val latch = CountDownLatch(1)
        val task = FutureTask {
            init.start()
            latch.await()
            value!!
        }
        init.registerResultHandler {
            value = it
            latch.countDown()
        }
        e.execute(task)
        return task
    }

    fun <T> handleStep(f: Future<T>, nextStep: CofunStep<T>) {
        try {
            val v = f.get()
            nextStep.doStep(v)
        }
        catch (e: ExecutionException) {
            nextStep.doStepWithException(e.cause!!)
        }
    }
}

abstract class CofunBase<R> : CofunInit<R> {
    protected var label = 1

    @Volatile
    private var resultHandler: ((R) -> Unit)? = null

    protected abstract fun main()

    internal fun nextStep() {
        main()
    }

    override fun start() {
        assert(label == 1)
        main()
    }

    val isFinished: Boolean
        get() = label <= 0

    final override fun registerResultHandler(handler: (R) -> Unit) {
        this.resultHandler = handler
    }

    protected fun handleResult(result: R) {
        resultHandler!!(result)
    }

}

fun main(args: Array<String>) {
    val wfm = FutureWFM(ForkJoinPool.commonPool())

    //    val f = wfm.cofun {
    //        println("started")
    //        val f1 = cofun { "subresult" }
    //        println("continued")
    //        "result: ${await(f1)}"
    //    }
    //
    //    f.get()

    val inner = object : CofunBase<String>() {

        override fun main() {
            when (label) {
                1 -> {
                    label = 0
                    handleResult("subresult")
                    return
                }
            }
        }

    }

//    println(wfm.cofun(inner).get())

    val outer = object : CofunBase<String>() {
        var result1: ResultBox<String>? = null

        override fun main() {
            when (label) {
                1 -> {
                    println("started")
                    val f1 = wfm.cofun(inner) // ?
                    println("continued")

                    // await f1
                    label = 2
                    result1 = ResultBox(this)
                    wfm.handleStep(f1, result1!!)
                    return
                }
                2 -> {
                    val result = result1!!.result
                    when (result) {
                        is ResultBox.Result.Exception -> {
                            throw result.exception
                        }
                        is ResultBox.Result.Value -> {
                            val tmp1 = result.value
                            label = 0
                            handleResult("result: $tmp1")
                            return
                        }
                    }
                }
            }
        }
    }

    println(wfm.cofun(outer).get())
}