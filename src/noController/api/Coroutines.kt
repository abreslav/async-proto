package noController.api

interface Coroutine<Ctx> {
    fun create(context: Ctx): Continuation<Unit, Ctx>
}

interface Continuation<in P, out Ctx> {
    val context: Ctx

    fun resume(parameter: P)

    fun resumeAndThrow(throwable: Throwable)
}


