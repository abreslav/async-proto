package noController.api

interface Coroutine<in Ctx> {
    fun create(context: Ctx): Continuation<Unit>
}

interface Continuation<in P> {
    fun resume(parameter: P)

    fun resumeAndThrow(throwable: Throwable)
}


