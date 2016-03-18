* Iterator (generator block)
  * hasNext() executes actions
  * finally and Disposable
* Tree traversal (yield returns value)
* Observable<Pair<S, S>> -> Observable<S> (await, yield, yield)
* Obs<A>, Obs<B> -> Obs<Pair<A, B>>
* Build pipeline
  * Rx
  * CompletableFuture
  * Kovenant
  * RatPack
* Java 8 Streams make pipelines
* Collection comprehensions
  * yield foreach
  * for as expression (special convention)
    * similar to forEach() convention
    * or use conventions for break and continue
      * reset API
  * transformWithIndex
    * Confun lambdas may have parameters
* Netty channels
* Go
  * channels
  * goroutines
  * defer
  * ???
* NIO
* Servlets
* Games
  * Time slicing/RealTime: decompose computations into cancellable steps
* AJAX
* Protocol state machines
* Cooperative multitasking protocols
* UI
  * invokeLater
  * Android: await(toast(...))
* The Maybe monad 