package noController.api

interface Coroutine<in C> {
    fun entryPoint(controller: C): Continuation<Unit>
}

interface Continuation<in P> {
    fun resume(data: P)

    fun resumeWithException(exception: Throwable)
}


