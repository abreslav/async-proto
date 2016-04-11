Links:
* C# new tasks https://github.com/dotnet/roslyn/issues/7169
* Scala.react http://infoscience.epfl.ch/record/176887/files/DeprecatingObservers2012.pdf


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
  
  
--------------  
Feature request: something like "async vals". Use case:
  
```
asyncFutures {
    val original = await(asyncLoadImage("...original...")) // creates a Future
    val overlay = await(asyncLoadImage("...overlay..."))   // creates a Future
    ...
    return applyOverlay(orig, over)
}
```
Problem: the `overlay` one isn't starting until the original one is done. We could say something like this:
  
```
asyncFutures {
    async val original = asyncLoadImage("...original...") // creates a Future
    async val overlay = asyncLoadImage("...overlay...")   // creates a Future
    ...
    return applyOverlay(orig, over)
}
```  
So that:
- the compiler checks that `await` is applicable (how would it know it must be `await` and not some other function?)
  - maybe say `await val` and use the name before the `val` as the name to look for?
- the rhs is executed
- the suspension and await call is not done until we use either of the variables
  - i.e. we remember what `asynLoadImage` returned (future), and await it only on the first access
  
Same for functions:

```
async fun foo() = ...

println(foo()) // await'ed automatically
```


Looks like this can be done in a library!

```
class Controller {
    suspend fun <T> Future<T>.getValue(p: KProperty<*>): T = await(this)
}

asyncFutures {
    val original by asyncLoadImage("...original...")
    val overlay by asyncLoadImage("...overlay...")
    ...
    return applyOverlay(orig, over)
}
```
--------   
  
  
  * use cases
    * async/await
      * Promise
    * generators
      * yield/yieldAll
    * ui
      * show a modal dialog from outside the UI thread
    * server
      * register a user, validate email, log them it
    * maybe
      * return default value on an intermediate null    
  * solution examples
  * design aspects
    * general concepts: controller, suspension point, type inference rules
    * control over the state machine: nextStep, hasFinished, finally, local variables/deserialization, reset 
    * syntax  
    * exceptions
      * altering the semantics of for loops
    * concurrency guarantees
    * constraints
      * minimizing allocations
      * compatibility with many frameworks
      * type-safety of the library code
      * unification of the code on different steps
  * automata examples
    * main principle: byte code transformation
    * tricky parts
      * loops
      * exceptions
      * finally
      * stack spilling
  * Proposal
    * async vals: promise on the right, but no promise type
    
    