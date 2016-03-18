Description:
  * When a value in the middle of computation evaluates to null, terminate the computation and return the default value
  
Similar cases:
  * Retry on null

Issues:
  * WFM can't have properties parameterized with a state object :(

``` kotlin
val e = maybe {
    // defaultResult = 5 - can't do this :(
    defaultResult(5)
    // would like to do .mb, but have to do .mb() instead
    val x = foo?.bar().mb()
    val y = x.baz.mb()
    x + y
}
```

``` kotlin
fun <T> maybe(cofun start: CofunStart<MaybeWFM, () -> Unit, MaybeState<T>): T? {
    val state = MaybeState<T>()
    start.setState(state)
    start.execute(MaybeFWF).invoke()
    return state.result        
}

object MaybeWFM {
    fun <T: Any> defaultResult(v: T, cofun step: CofunStep<Unit, MaybeState<T>>) {
        step.state.defaultResult = v
        step.exec(Unit)
    }
    
    fun <T: Any> T?.mb(cofun step: ConfunStep<T, MaybeState<T>>) {
        if (this == null) return
        step.exec(this)
    }
    
    operator fun complete(v: T, last: Last<MaybeState<T>>) {
        last.result = v
    }
}

class MaybeState<T: Any> {
    var defaultResult: T? = null
    var returnedResult: T? = null
    
    val result: T?
        get() = returnedResult ?: defaultResult
}

```