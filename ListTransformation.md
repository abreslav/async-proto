``` kotlin
val e = transform(list) {
    item ->
    val a = item.a
    if (!a.isValid()) skip() // continue
    val b = a.b
    if (b.isFailed()) stop() // break 
    foo(b)
}
```

``` kotlin
fun <A, T> transform(input: List<A>, cofun start: CofunStart<TransformWFM, (A) -> Unit, List<T>): List<T> {
    val result = ArrayList<T>(input.size())
    start.setState(state)
    for (item in input) {
        start.reset()
        start.execute(transformWFM).invoke(item)
        if (start.signal == true) {
            break
        }
    }
    result.trimToSize()
    return result        
}

object TransformWFM {
    fun <T> skip(cofun control: ConfunStep<Nothing, MaybeState<T>>) {
        control.reset()
    }
    
    fun stop(cofun step: ConfunStep<Nothing, MaybeState<T>>) {
        step.setSignal(true)        
    }
    
    operator fun complete(v: T, last: Last<MaybeState<T>>) {
        last.add(v)
    }
}
```