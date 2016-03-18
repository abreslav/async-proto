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
label 1:
    label = 2
    wfm.defaultResult(5, this)
    return
label 2:
    if (exception != null) throw exception
    val tmp = foo?.bar
    label = 3
    tmp.mb(this)
    return
label 3:
    if (exception != null) throw exception
    x = result
    val tmp = x.baz
    label = 4
    tmp.mb(this)
    return
label 4:
    if (exception != null) throw exception
    y = result
    complete(x + y, this)
    return
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
    
    fun complete(v: T, cofun last: Last<MaybeState<T>>) {
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