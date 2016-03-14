
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class NaiveFutureWFM(val e: ExecutorService) {
    fun <T> cofun(body: NaiveFutureWFM.() -> T): Future<T> {
        val task = FutureTask {
            body()
        }
        e.execute(task)
        return task
    }

    fun <T> await(f: Future<T>): T {
        return f.get()
    }
}

class FutureTest {
    val wfm = NaiveFutureWFM(ForkJoinPool.commonPool())

    @Test
    fun naive() {
        val f = wfm.cofun {
            val f1 = cofun { "subresult" }
            "result: ${await(f1)}"
        }

        Assert.assertEquals("result: subresult", f.get())
    }

}