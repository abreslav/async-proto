package proto.async.cfuture

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool

interface CofunInit<T> {
    fun start()
}

interface CofunStep {
    fun doStep()
    fun doStepWithException(e: Throwable)
}

// -------- Pipeline builder instead of WFM

class FutureWFM(val e: ExecutorService) {
    fun <T> cofun(init: CofunInit<T>): CompletableFuture<T> {
        val cf = CompletableFuture<T>()
        e.execute {init.start()}
        return cf
    }

    fun <T> handleResult(f: CompletableFuture<T>, result: T) {
        f.complete(result)
    }

    fun <T> handleException(f: CompletableFuture<T>, exception: Throwable) {
        f.completeExceptionally(exception)
    }

    fun <T> yieldFor(f: CompletableFuture<T>, nextStep: CofunStep) {
        f.whenComplete { t, throwable ->
            nextStep.doStep()
        }
    }

    fun <T> getResult(f: CompletableFuture<T>): T {
        return f.join()
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

//    final override fun registerResultHandler(handler: (R) -> Unit) {
//        this.resultHandler = handler
//    }

//    protected fun handleResult(result: R) {
//        resultHandler!!(result)
//    }

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


    //    println(innerF.join())

    var outerF: CompletableFuture<String>? = null
    val outer = object : CofunBase<String>() {

        lateinit var f1: CompletableFuture<String>

        override fun main() {
            when (label) {
                1 -> {
                    println("started")
                    f1 = inner(wfm) // ?
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
                    wfm.handleResult(outerF!!, "result: $tmp1")
                    return
                }
            }
        }
    }
    outerF = wfm.cofun(outer)

    println(outerF.get())
}

private fun inner(wfm: FutureWFM): CompletableFuture<String> {
    var innerF: CompletableFuture<String>? = null
    val inner = object : CofunBase<String>() {

        override fun main() {
            when (label) {
                1 -> {
                    println("inner started")
                    label = 0
                    wfm.handleResult(innerF!!, "subresult")
                    return
                }
            }
        }

    }
    innerF = wfm.cofun(inner)
    return innerF
}