* How to implement passing the result of a suspending call back to the coroutine?
  * write to a field (one of two field in case we want to eliminate boxing: `Object` and `long`)
  * have a parameter on the main method of the state machine (two, to eliminate boxing), and assign to fields only 
    if we need to store the result between steps 

* Options/Questions
  * Should all suspension points have the controller as a receiver?
    * Yes, but should we allow extensions as suspension points?
  * What signature does a callable reference `::await` have inside a coroutine? 
    Should it have an explicit continuation parameter or have it bound implicitly?


* Design Choices
 * Controller and Context vs Two-in-one
  * pro: a lot saner type signatures: Coroutine<Ctx>, Continuation<P>
  * pro: simpler mental model for yield type-checking
  * pro: no need for inferFrom/unify modifier
  * con: need to extend CompletableFuture or have to create an extra instance every time for Futures
  * con: ? it's an implicit receiver in the comlambda, may cause type-checking loops, if we ever want to allow 
    extensions as suspension points (fun Controller<out Number>.bar())
 * (p) -> Conroutine -> Continuation vs (p, Controller) -> Continuation  
 

There are the following issues related to exception handling:
 * what happens if an exception occurs in the coroutine code?
 * who handles an exception that occurs in an asynchronous computation called from a coroutine when the coroutine is 
   suspended?
 * how to guarantee execution of `finally` blocks if a `try/finally` occurs in a coroutine?
   * Disposable iterators and `for` loops 
 * what happens if a suspension point occurs in a `finally` block or a `catch` block?
 * invalidation of the state machine upon an unhandled exception
   * throw the same exception upon access to the state machine vs wrap it
   * big try-catch around the whole state machine
     * can we jump into the middle of a catch block?
 * what can and can not be called in a finally block inside a coroutine 


Cancellation of futures

API Naming

* Coroutine interface
* Continuation interface
  * run/resume/step
* State machine class in the byte code
  * the method containig the state machine code: `main`, `invoke`, `machine`, `run`/`resume`/`step` like in Continuation 
  * the `label`

Syntax

* Suspension point
  * simply `yield(foo)`
  * designated: `$yield(foo)`, `^await(foo)`, `suspend yield(foo)` etc
* <inferFrom T>
  * <context T>
  * <unify T>