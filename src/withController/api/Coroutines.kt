package withController.api

interface Coroutine<in Ctl, Ctx> {
    fun create(controller: Ctl, context: Ctx): Continuation<Unit, Ctx>
}

interface Continuation<in P, out Ctx> {
    val context: Ctx

    fun resume(parameter: P)

    fun resumeAndThrow(throwable: Throwable)
}


