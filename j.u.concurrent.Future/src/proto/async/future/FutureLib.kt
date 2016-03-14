package proto.async.future

import java.util.concurrent.*

interface CofunInit<T> {
    fun start()
    fun registerResultHandler(handler: (T) -> Unit)
    // exception handler?
}

interface CofunStep {
    fun doStep()
    fun doStepWithException(e: Throwable)
}

// --------

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

    fun <T> yieldFor(f: Future<T>, nextStep: CofunStep) {
        try {
            f.get()
            nextStep.doStep()
        }
        catch (e: ExecutionException) {
            nextStep.doStepWithException(e.cause!!)
        }
    }

    fun <T> getResult(f: Future<T>): T {
        return f.get()
    }
}

abstract class CofunBase<R> : CofunInit<R>, CofunStep {
    protected var label = 1
    protected var exception: Throwable? = null

    @Volatile
    private var resultHandler: ((R) -> Unit)? = null

    protected abstract fun main()

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

    override fun doStep() {
        main()
    }

    override fun doStepWithException(e: Throwable) {
        exception = e
        main()
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

        lateinit var f1: Future<String>

        override fun main() {
            when (label) {
                1 -> {
                    println("started")
                    f1 = wfm.cofun(inner) // ?
                    println("continued")

                    // await f1
                    label = 2
                    wfm.yieldFor(f1, this)
                    return
                }
                2 -> {
                    if (exception != null) throw exception!!
                    val tmp1 = wfm.getResult(f1)
                    label = 0
                    handleResult("result: $tmp1")
                    return
                }
            }
        }
    }

    println(wfm.cofun(outer).get())
}