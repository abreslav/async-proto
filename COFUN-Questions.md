Links:
* C# new tasks https://github.com/dotnet/roslyn/issues/7169
* Scala.react http://infoscience.epfl.ch/record/176887/files/DeprecatingObservers2012.pdf

* How to avoid boxing with Continuation.resume(T)

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


- keyword-less vs keyword-based syntax
- handling of `finally` blocks
- stack spilling for normal calls
- stack spilling for constructor calls (mind the class initialization issue, check otehr languages)
- irreducible CFGs may degrade performance
- volatile fields to store state
- nullify fields referencing objects when corresponding local go out of scope (to prevent memory leaks)

- C++
 - Critique to the C++ approach: https://habrahabr.ru/post/278267/
 - [Coroutines belong in a TS](http://www.open-std.org/jtc1/sc22/wg21/docs/papers/2015/p0158r0.html)
 - [resumable functions - async and await](http://meetingcpp.com/index.php/br/items/resumable-functions-async-and-await.html)
 - [Habrahabr post in Russian](https://habrahabr.ru/post/278267/)
- C#
 - async and generators are separate concepts
- Dart
- F#
- Go
- TypeScript
- Rust
- Scala
- Hack
- JavaScript
- Python



Cancellation of futures

API Naming

* Coroutine interface
  * the "create" function: entryPoint? firstStep?
* Continuation interface
  * run/resume/step
* State machine class in the byte code
  * the method containig the state machine code: `main`, `invoke`, `machine`, `run`/`resume`/`step` like in Continuation 
  * the `label`
* handlers: completion/result or exception

Syntax

* Coroutine builder
  * modifier on the parameter: `coroutine c: () -> Coroutine<...>`
  * special type syntax: `c: () => Controller` or other
  * modifier on the function?
  * call-site distinction?
* Suspension point
  * simply `yield(foo)`
  * designated: `$yield(foo)`, `^await(foo)`, `suspend yield(foo)` etc
* Suspending function
  * `suspend` modifier?
* <inferFrom T>
  * <context T>
  * <unify T>
  
  
> With the help of _delegated properties_, the example above may be simplified even further:
```
async {
    val original by asyncLoadImage("...original...")
    val overlay by asyncLoadImage("...overlay...")
    ...
    // access to the properties (i.e. the getValue()) can be defined as a suspending function,
    // so there's no need for explicit await()
    return applyOverlay(original, overlay)
}
```

  
  
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
    
    