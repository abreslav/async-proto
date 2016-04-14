# KEEP-COFUN. Coroutines for Kotlin

Document type: Language Design Proposal
Document authors: Andrey Breslav
Other contributors: Vladimir Reshetnikov, Stanislav Erokhin, Ilya Ryzhenkov
Document status: under review
Prototype implementation: not started  

## Abstract

We propose to add coroutines to Kotlin. This concept is also known as or partly covers

- generators/yield
- async/await
- (delimited or stackless) continuations
 
It is an explicit goal of this proposal to make it possible to utilize Kotlin coroutines as wrappers for different existing asynchronous APIs (such as Java NIO, different implementations of Futures, etc).  

## Use cases

A coroutine can be thought of as a _suspendable computation_, i.e. the one that can suspend at some points and later continue (possibly on another thread). Coroutines calling each other (and passing data back and forth) can form the machinery for cooperative multitasking, but this is not exactly the driving use case for us.
 
### Asynchronous computations 
 
First motivating use case for coroutines is asynchronous computations (handled by async/await in C# and other languages). Let's take a look at how such computations are done with callbacks. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified):

```
// asynchronously read into `buf`, and when done run the lambda
inFile.read(buf) {
    // this lambda is executed when the reading completes
    bytesRead ->
    ...
    ...
    val newData = process(buf, bytesRead)
    
    // asynchronously write from `buf`, and when done run the lambda
    outFile.write(buf) {
        // this lambda is executed when the writing completes
        ...
        ...
        outFile.close()          
    }
}
```

Note that we have a callback inside a callback here, and while it saves us from a lot of boilerplate (e.g. there's no 
need to pass the `buf` parameter explicitly to callbacks, they just see it as part of their closure), the indentation
levels are growing every time, and one can easily anticipate the problems that may come at nesting levels greater than one (google for "callback hell" to see how much people suffer from this in current JavaScript, where they have no choice other than use callback-based APIs).

This same computation can be expressed straightforwardly as a coroutine (provided that there's a library that adapts
 the I/O APIs to coroutine requirements):
 
```
asyncIO {
    // suspend while asynchronously reading
    val bytesRead = inFile.read(buf) 
    // we only get to this line when reading completes
    ...
    ...
    val newData = process(buf, bytesRead)
    // suspend while asynchronously writing   
    outFile.write(buf)
    // we only get to this line when writing completes  
    ...
    ...
    outFile.close()
}
```

The calls to `read()` and `write()` here are treated specially by the coroutine: it suspends at such a call (which does not mean blocking the thread it's been running on) and resumes when the call has completed. If we squint our eyes just enough to imagine that all the code after `read()` has been wrapped in a lambda and passed to `read()` as a callback, and the same has been done for `write()`, we can see that this code is the same as above, only more readable. (Making such lambdas efficient is important, and we describe it below.)  

It's our explicit goal to support coroutines in a very generic way, so in this example, `asyncIO {}`, `File.read()` and `File.write()` are just **library functions** geared for working with coroutines (details below): `asyncIO` marks the scope of a coroutine and controls its behavior, and `read/write` are recognized as special _suspending functions_, for they suspend the computation and implicitly receive continuations.  

Note that with explicitly passed callbacks having an asynchronous call in the middle of a loop can be tricky, but in a coroutine it is a perfectly normal thing to have:

```
asyncIO {
    while (true) {
        // suspend while asynchronously reading
        val bytesRead = inFile.read(buf)
        // continue when the reading is done
        if (bytesRead == -1) break
        ...
        val newData = process(buf, bytesRead)
        // suspend while asynchronously writing
        outFile.write(buf) 
        // continue when the writing is done
        ...
    }
}
```

One can imagine that handling exceptions is also a bit more convenient in a coroutine.

There's another style of expressing asynchronous computations: through futures (and their close relatives — promises).
We'll use an imaginary API here, to apply an overlay to an image:

```
val future = runAfterBoth(
    asyncLoadImage("...original..."), // creates a Future 
    asyncLoadImage("...overlay...")   // creates a Future
) {
    original, overlay ->
    ...
    applyOverlay(original, overlay)
}
return future.get()
```

With coroutines, this could be rewritten as

```
asyncFutures {
    val original = asyncLoadImage("...original...") // creates a Future
    val overlay = asyncLoadImage("...overlay...")   // creates a Future
    ...
    // suspend while awaiting the loading of the images
    // then run `applyOverlay(...)` when they are both loaded
    return applyOverlay(await(original), await(overlay))
}
```

Again, less indentation and more natural composition logic (and exception handling, not shown here), and no building async/await into teh language: `asyncFuture {}` and `await()` are functions in a library. 

> With the help of _delegated properties_, the example above may be simplified even further:
```
asyncFutures {
    val original by asyncLoadImage("...original...")
    val overlay by asyncLoadImage("...overlay...")
    ...
    // access to the properties (i.e. the getValue()) can be defined as a suspending function,
    // so there's no need for explicit await()
    return applyOverlay(original, overlay)
}
```

### Generators

Another typical use case for coroutines would be lazily computed sequences (handled by `yield` in C#, Python 
and many other languages):
 
```
val seq = input.filter { it.isValid() }.map { it.toFoo() }.filter { it.isGood() }
```

This style of expressing (lazy) filtering/mapping is often acceptable, but has its drawbacks:

 - `it` is not always fine for a name, and a meaningful name has to be repeated in each lambda,
 - multiple intermediate objects created,
 - non-trivial control flow and exception handling are a challenge.
 
As a coroutine, this becomes close to a "comprehension":
 
```
val seq = input.transform {
    if (it.isValid()) {      // "filter"
        val foo = it.toFoo() // "map"
        if (foo.isGood()) {  // "filter"
            // suspend until the consumer of the sequence calls iterator.next()
            yield(foo)         
        }                
    }
} 
```

This form may look more verbose in this case, but if we add some more code in between the operations, or some non-trivial control flow, it has invaluable benefits:

```
val seq = transform {
    yield(firstItem) // suspension point

    for (item in input) {
        if (!it.isValid()) break // don't generate any more items
        val foo = it.toFoo()
        if (!foo.isGood()) continue
        yield(foo) // suspension point        
    }
    
    try {
        yield(lastItem()) // suspension point
    }
    finally {
        // some finalization code
    }
} 
```

This approach also allows to express `yieldAll(sequence)` as a library functions (as well as `generate {}` and `yeild()` are), which simplifies joining lazy sequences and allows for efficient implementation (a naïve one is quadratic in the depth of the joins).  

### More use cases
 
Coroutines can cover many more use cases, including these:  
 
* Channel-based concurrency (aka goroutines and channels);
* UI logic involving off-loading long tasks from the event thread;
* Background processes occasionally requiring user interaction, e.g., show a modal dialog;
* Communication protocols: implement each actor as a sequence rather than a state machine;
* Web application workflows: register a user, validate email, log them in (a suspended coroutine may be serialized and stored in a DB).

## Coroutines overview

This section gives a bird's-eye view of the proposed language mechanisms that enable writing coroutines and libraries that govern their semantics.  

NOTE: all names, APIs and syntactic constructs described below are subject to discussion and possible change.

### Terminology

* A _coroutine_ -- a block of code (possibly, parameterized) whose execution can be suspended and resumed potentially multiple times (possibly, at several different points), yielding the control to its caller.

Syntactically, a coroutine looks exactly as a function literal `{ x, y -> ... }`. A coroutine is distinguished by the compiler from a function literal based on the special type context in which it occurs. A coroutine is typechecked using different rules and in a different way than a regular function literal.
 
> Note: Some languages with coroutine support allow coroutines to take forms both of an anonymous function and of a method body. Kotlin supports only one syntactic flavor of coroutines, resembling function literals. In case where a coroutine in the form of a method body would be used in another language, in Kotlin such method would typically be a regular method with an expression body, consisting of an invocation expression whose last argument is a coroutine: 
 ```
 fun asyncTask() = async { ... }
 ```

* _Suspension point_ -- a special expression in a coroutine that designates a point where the execution of the coroutine is suspended. Syntactically, a suspension point looks as an invocation of a function that's marked with a special modifier on the declaration site (other syntactic options may be considered at some point). 

* Such a function is called a _suspending function_, it receives a _continuation_ object as an argument which is passed implicitly from the calling coroutine.

* _Continuation_ is like a function that begins right after one of the suspension points of a coroutine. For example:
```
generate {
    for (i in 1..10) yield(i * i)
    println("over")
}  
```  

Here, every time the coroutine is suspended at a call to `yield()`, _the rest of its execution_ is represented as a continuation, so we create 10 continuations: first runs the loop with `i = 2` and suspends, second runs the loop with `i = 3` and suspends, etc, the last one prints "over" and exits the coroutine. 

### Implementation through state machines

It's crucial to implement continations efficiently, i.e. create as few classes and objects as possible. Many languages implement them through _state machines_, and in the case of Kotlin this approach results in the compiler creating only one class and one instance per coroutine.   
 
Main idea: a coroutine is compiled to a state machine, where states correspond to suspension points. Example: let's take a coroutine with two suspension points:
 
```
val a = a()
val y = await(foo(a)) // suspension point
b()
val z = await(bar(a, y)) // suspension point
c(z)
``` 
 
For this coroutine there are three states:
 
 * initial (before any suspension point)
 * after the first suspension point
 * after the second suspension point
 
Every state is an entry point to one of the continuations of this coroutine (the first continuation "continues" from the very first line). 
 
The code is compiled to an anonymous class that has a method implementing the state machine, a field holding the current state of the state machine, and fields for local variables of the coroutines that are shared between states (there may also be fields for the closure of the coroutine, but in this case it's empty). Here's pseudo-bytecode for the coroutine above: 
  
```
class <anonymous_for_state_machine> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void resume(Object data) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        if (label == 2) goto L2
        else throw IllegalStateException()
        
      L0:
        // data is expected to be `null` at this invocation
        a = a()
        label = 1
        await(foo(a), this) // 'this' is passed as a continuation 
        return
      L1:
        // external code has resumed this coroutine passing the result of await() as data 
        y = (Y) data
        b()
        label = 2
        await(bar(a, y), this) // 'this' is passed as a continuation
        return
      L3:
        // external code has resumed this coroutine passing the result of await() as data 
        Z z = (Z) data
        c(z)
        label = -1 // No more steps are allowed
        return
    }          
}    
```  

Note that:
 * exception handling and some other details are omitted here for brevity,
 * there's a `goto` operator and labels, because the example depicts what happens in the byte code, not the source code.

Now, when the coroutine is started, we call its `resume()`: `label` is `0`, and we jump to `L0`, then we do some work, 
set the `label` to the next state — `1` and return (which is — suspend the execution of the coroutine). 
When we want to continue the execution, we call `resume()` again, and now it proceeds right to `L1`, does some work, sets
the state to `2`, and suspends again. Next time it continues from `L3` setting the state to `-1` which means "over, 
no more work to do". The details about how the `data` parameter works are given below.

A suspension point inside a loop generates only one state, because loops also work through (conditional) `goto`:
 
```
var x = 0
while (x < 10) {
    x += await(nextNumber())
}
```

is generated as

```
class <anonymous_for_state_machine> : Coroutine<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    int x
    
    void resume(Object data) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        else throw IllegalStateException()
        
      L0:
        x = 0
      LOOP:
        if (x > 10) goto END
        label = 1
        await(nextNumber(), this) // 'this' is passed as a continuation 
        return
      L1:
        // external code has resumed this coroutine passing the result of await() as data 
        x += ((Integer) data).intValue()
        label = -1
        goto LOOP
      END:
        label = -1
        return
    }          
}    
```  

Note: boxing can be eliminated here, through having another parameter to `resume()`, but we are not getting into these details.

## The building blocks

As mentioned above, one of the driving requirements for this proposal is flexibility: we want to be able to support many existing asynchronous APIs and other use cases and minimize the parts hard-coded into the compiler.
  
As a result, the compiler is only responsible for transforming coroutine code into a state machine, and the rest is left to libraries. We provide more or less direct access to the state machine, and introduce building blocks that frameworks and libraries can use: _coroutine builders_, _suspending functions_ and _controllers_.

### A lifecycle of a coroutine

So, the only thing that happens "magically" is the creation of a state machine. The coroutine object that encapsulates the state machine (or rather a factory capable of creating those objects) is passed to a function such as `async {}` or `generator {}` from above; we call these functions _coroutine builders_. The builder function's biggest responsibility is to define a _controller_ for the coroutine. Controller is an object that determines which suspension functions are available inside the coroutine, how the return value of the coroutine is processed, and how exceptions are handled. 

Normally, a builder function creates a controller, passes it to a factory to obtain a working instance of the coroutine, and returns some useful object: Future, Sequence, AsyncTask or alike. The returned object is the public API for the coroutine whose inner workings are governed by the controller.
    
To summarize:
- coroutine builder is an entry point that takes a coroutine as a block of code and returns a useful object to the client,
- controller is an object used internally by the library to define and coordinate all aspects of the coroutine's behavior.
        
### Library interfaces

Here's the minimal version of the core library interfaces related to coroutines (there will likely be extra members to handle advanced use cases such as restrating the coroutine from the beginning or serializing its state):

``` kotlin
interface Coroutine<C> {
   fun entryPoint(controller: C): Continuation<Unit>
}

interface Continuation<P> {
   fun resume(data: P)
   fun resumeWithException(exception: Throwable)
}
```

So, a typical coroutine builder would look like this:
 
```
fun <T> async(coroutine c: () -> Coroutine<FutureController<T>>): Future<T> { 
    // get an instance of the coroutine
    val coroutine = c() 
    
    // controllers will be discussed below
    val controller = FutureController<T>()
     
    // to start the execution of the coroutine, obtain its fist continuation
    // it does not take any parameters, so we pass Unit there
    val firstContinuation = coroutine.entryPoint(controller)
    firstContinuation.resume(Unit)
    
    // return the Future object that is created internally by the controller
    return controller.future
}    
``` 

The `c` parameter normally receives a lambda, and its `coroutine` modifier indicates that this lambda is a coroutine, so its body has to be translated into a state machine. Note that such a lambda may have parameters which can be naturally expressed as `(Foo, Bar) -> Coroutine<...>`.  
  
The usual workflow of a builder is to first pass the user-defined parameters to the coroutine. In our example there're no parameters, and this amounts to calling `c()` which returns a `Coroutine` instance. Then, we create a controller and pass it to the `entryPoint()` method of a the `Coroutine`, to obtain the first `Continuation` object whose `resume()` starts the execution of the coroutine. (Passing `Unit` to `resume()` may look weird, but it will be explained below.)    

NOTE: Technically, one could implement the `Coroutine` interface and pass a lambda returning that custom implementation to `asyncExample`. 

NOTE: To allocate fewer objects, we can make the state machine itself implement `Continuation`, so that its `resume` is the main method of the state machine. In fact, the initial lambda passed to the coroutine builder, `() -> Coroutine<...>` can be also implemented by the same state machine object. Sometimes the lambda and the `entryPoint()` function may be called more than once and with different arguments yielding multiple instances of the same coroutine. To support this case, we can teach the sole lambda-coroutine-continuation object to clone itself.   

### Controller

The purpose of the controller is to govern the semantics of the coroutine. A controller can define 
- suspending functions,
- handlers for coroutine return values,
- exception handlers for exceptions thrown inside the coroutine.

Typically, all suspending functions and handlers will be members of the controller. We may need to allow extensions to the controller as suspending functions, but this should be through an opt-in mechanism, because many implementations would break if any unanticipated suspension points occur in a coroutine (for example, if an `async()` call happens unexpectedly among `yield()` calls in a basic generator, iteration will end up stuck leading to undesired behavior).

It is a language rule that suspending functions (and probably other specially designated members of the controller) are available in the body of a coroutine without qualification. In this sense, a controller acts similarly to an [implicit receiver](https://kotlinlang.org/docs/reference/extensions.html#declaring-extensions-as-members), only it exposes only some rather than all of its members.      

### Suspending functions

To recap: a _suspension point_ is an expression in the body of a coroutine which causes the coroutine's execution to
suspend until it's explicitly resumed by someone. Suspension points are calls to specially marked functions called _suspending functions_. 

A suspending function looks something like this:
  
```
suspend fun <T> await(f: CompletableFuture<T>, c: Continuation<T>) {
    f.whenComplete { result, throwable ->
        if (throwable == null)
            // the future has been completed normally
            c.resume(result) 
        else          
            // the future has completed with an exception
            c.resumeWithException(throwable)
    }
}
``` 

The `suspend` modifier indicates that this function is special, and its calls are suspension points that correspond to states of a state machine. 

When `await(f)` is called in the body of the coroutine, the second parameter (a continuation) is not passed explicitly, but is injected by the compiler. After `await()` returns, the coroutine is suspended and the control is transferred to its caller. The execution of the coroutine is resumed only when the future `f` is completed, and `resume()` is called on the continuation `c` (as per the handler registered with the `CompletableFuture` object).
 
The value passed to `resume()` acts as the **return value** of `await()` calls in the body of the coroutine. Since this value will only be known when the future is completed, `await()` can not return it right away, and the return type in _any_ suspending function declaration is always `Unit`. When the coroutine is resumed, the result of the last suspending call is passed as a parameter to the `resume()` of the continuation. (This is why the `entryPoint()` gives a `Continuation<Unit>` — there's no call to return a result for, so we simply pass a placeholder object.)  

Consider this example of a coroutine:
 
```
async {
    val x = await(foo) // suspension point
    println(x)
}
```

Here's the state machine code generated for it:
 
```
void resume(Object data) { 
    if (label == 0) goto L0
    if (label == 1) goto L1
    else throw IllegalStateException()
     
    L0:
      label = 1
      contoller.await(foo, this)
      return
    L1:
      Foo x = data as Foo  // the value of `await(foo)` is passed back as `data`
      println(x)
      label = -1
      return
}  
```

Note a detail that has been omitted before: `await()` is called as a member of the controller (which is a field in the state machine class). 

The first time (`label == 0`) the value of `data` is ignored, and the second time (`label == 1`) it is used as the result of `await(foo)`.  
 
So, a suspension point is a function call with an implicit continuation parameter. Such functions can be called in this
 form only inside coroutines. Elsewhere they may be called only with both parameters passed explicitly.
 
Note that the library has full control over which thread the continuation is resumed at and what parameter is passed to it. It may even continue the execution of the coroutine synchronously by immediately calling `resume()` on the same thread. 
    
NOTE: some may argue that it's better to make suspension points more visible at the call sites, e.g. prefix them with
  some keyword or symbol: `#await(foo)` or `suspend await(foo)`, or something. These options are all open for discussion.   
  
### Result handlers

A coroutine body looks like a regular lambda in the code and, like a lambda, it may have parameters and return a value. Handling parameters is covered above, and returned values are passed to a designated function (or functions) in the controller:
 
```
class FutureController<T> {
    val future: CompletableFuture<T> = ...
    
    operator fun handleResult(t: T, c: Continuation<Nothing>) {
        future.complete(t)    
    }
} 
``` 
 
The `handleResult()` function is called on the last expression in the body of a coroutine as well as on each explicit `return` from it:
   
```
val r = async {
    if (...) 
        return default // this calls `handleResult(default)`
    val f = await(foo)
    f + 1 // this calls `handleResult(f + 1)`           
}   
```   
 
As any function, `handleResult()` may be overloaded, and if a suitable overload is not available for a returned expression, it is a compilation error. If no `handleResult()` is defined in the controller, the last expression in the body of the coroutine is ignored and `return` with an argument is forbidden (`return Unit` may be allowed).
 
Note: the continuation parameter in the result handler is provided for uniformity, and may be used for advanced operations such as resetting the state machine or serializing its state. 
 
### Exception handlers
 
Handling exceptions in coroutines may be tricky, and some details of it are to be refined later, but the basics are as follows.
 
A controller may define an exception handler:
 
```
operator fun handleException(e: Throwable, c: Continuation<Nothing>) {
    future.completeExceptionally(e) 
}
```

This handler is called when an unhandled exception occurs in the coroutine (the coroutine itself becomes invalid then and can not be resumed any more). Technically, it is implemented by wrapping the whole body of the coroutine (the whole state machine) into a try/catch block whose catch calls the handler:
  
```
void resume(Object data) {
    if (label == 0) goto L0
    else if (label == 1) goto L1
    else throw IllegalStateException()

    try {        
      L0:
        ...
        return
      L1:
        ...
        return
    } catch (Throwable e) {
        label = -2 // invalidate the coroutine
        controller.handleException(e)     
    }          
}
```  

Exception handlers can not be overloaded.

TODO: there's an issue of handling `finally` blocks so that they may be executed by the controller no matter how the coroutine was completed. 
 
### Continuing with exception

When exceptions occur in asynchronous computations, they may be handled by the controller itself, or passed to the user code in the coroutine to be handled there (this depends on the design decision made by the library author).
 
As shown above, the `Continuation` interfaces has a member function for resuming the coroutine with exception:
 
```
fun resumeWithException(exception: Throwable)
```
 
If a controller calls this function, the exception passed to it is re-thrown right after the coroutine is resumed (and thus it behaves as if the suspending call has thrown it):
 
```
async {
    println("Starting the coroutine")
    try {
        val x = await(throwingFuture)
        println(x)
    }
    catch (e: MyException) {
        // if the controller calls c.resumeWithException(e), 
        // the execution ends up here
        report(e)
    } 
    println("Ending the coroutine")
}
```

The way it is implemented in the byte code is as follows:

```
void resume(Object data) { doResume(data, null) }
void resumeWithException(Throwable exception) { doResume(null, exception) }

private void doResume(Object data, Throwable exception) { 
    if (label == 0) goto L0
    if (label == 1) goto L1
    else throw IllegalStateException()
    
    // this try-catch is the compiler-generated one, for unhandled exceptions
    try {     

      L0:
        println("Starting the coroutine")
        // this try-catch is written by the user
        try {  
            label = 1
            contoller.await(throwingFuture, this)
            return
      L1:
            // if the coroutine was resumed with exception, throw it
            if (exception != null) throw exception
            
            // if we ended up here, then there was no exception, 
            // and `data` holds the result of `await(throwingFuture)`
            Foo x = data as Foo  
            println(x)
        } catch (MyException e) {
           report(e)
        }
        
        println("Ending the coroutine")
        label = -1
        return
        
    } catch (Throwable e) {
        label = -2 // invalidate the coroutine
        controller.handleException(e)     
    }          
}  
```

Note that suspending in `finally` blocks will likely not be supported, at least in the nearest release.
 
## Type-checking coroutines 



## Complete code examples
 

 
 
 
## Type-checking coroutines 
 
The type of the controller is captured in the type-arguments of the `Coroutine` object, and thus is known to the type 
checker, as well as two other important things: the parameters of the coroutine and the type of its customizable internal 
state object (not to be confused with the current state of the state machine, which is always an `Int`, and named `label`
in the code examples above):
  
```
interface Coroutine<C, P, S> { ... }
```  

These types are fixed (maybe except the state type S) at the point of passing the body of a coroutine to the builder, 
because the builder specifies the full type of a coroutine. For example, here's a builder that creates Futures that are
computed on the `ForkJoinPool.commonPool()` from coroutines that take no parameters:
 
```
fun <T> asyncExample(body: Coroutine<CommonPoolFuturesController, () -> Unit, CompletableFuture<T>>): Future<T> {
    // some way of setting the controller
    body.controller = CommonPoolFuturesController 

    // remember the future we need to complete later, when the coroutine is finished
    val f = CompletableFuture<T>()    
    body.state = f

    // run the first step of the state machine in the ForkJoinPool
    CommonPoolFuturesController.exec { body.firstStep().run(null) }

    // return the created future
    return f
}
```

This function may be called like this:

```
val future = asyncExample {
    await(future)
}
```

Here, `await` is a member of the controller, which may be defined like this:

```
object CommonPoolFuturesController {
    fun <T> await(f: CompletableFuture<T>, continuation c: Continuation<T, *>) {
        f.whenComplete { t, throwable ->
            if (throwable != null) 
                c.runWithException(throwable)
            else          
                c.run(t)
        }
    }

    operator fun <T> handleResult(result: T, coroutine: CompletedCoroutine<CompletableFuture<T>>) {
        coroutine.state.complete(result)
    } 
    
    operator fun handleException(exception: Throwable, coroutine: : CompletedCoroutine<CompletableFuture<T>>) {
        coroutine.state.completeExceptionally(exception)
    }
}
```
 
Note that interfaces `Continuation` and `CompletedCoroutine` (the last one is passed to completion handlers: one for 
result, i.e. normal termination, the other for exception, i.e. abnormal termination) both have a type-parameter: this is 
`S`, the custom state, and it must be compatible with the one of the builder that this coroutine is passed to:
  
```
interface Coroutine<C, P, S> { ... }
interface Continuation<P, S> { ... }
interface CompletedCoroutine<S> { ... }
```

NOTE: it looks like `CompletedCoroutine<S>` may be expressed as `Continuation<Nothing, S>`, but it's up to discussion.

So, a **typing rule**: _all the `S` type-arguments must agree inside a coroutine_.

Now, if we want a generator, all its `yield`'s must agree on the type of the value yielded: 

```
val seq = generate {
    while (true) {
        yield(Random.nextInt())
        yield(0)
    }
}
```

Here, the compiler must be able to look at the arguments to `yield` and deduce that the result of `generate` is 
`Sequence<Int>`. This is the kind of analysis that's done for `return` expressions: the type checker collects all 
`return`'s in a lambda and finds a common return type for their expressions. The difference here is that `yield` is
_just a function_, albeit a suspension point. So, we need to mark it somehow to tell the compiler to treat it specially.
Note that there may be more than one such function in the same controller, for example:
    
```    
val seq1 = generate {
    while (true) {
        yieldAll(seq.take(5))
        yield(0)
    }
}
```

This sequence has five items of the previous one (random numbers and zeros), then a zero, then another five, and so on.
And the type of seqeuence in `yieldAll` must agree with the type passed to `yield`: one is `Sequence<Int>`, the other is
`Int`.
 
We propose to mark a type parameter of such a function as `inferFrom`, so that when `S` parameters are put into agreement,
the one in the builder application could be inferred from those marked `inferFrom`. Yes, the inferred type is the `S`:

NOTE: the name of the modifier (as well as its applicability rules) is up to discussion.
  
```
fun <T> generate(coroutine: Coroutine<GeneratorsController, () -> Unit, LazySequenceImpl<T>): Sequence<T> {
    coroutine.controller = GeneratorsController
    val seq = LazySequenceImpl<T>
    coroutine.state = seq
    seq.step = coroutine.firstStep()
    return seq 
}

object GeneratorsController {
    fun <inferFrom T> yield(value: T, continuation c: Continuation<Unit, LazySequenceImpl<T>>) {
        c.state.addValue(value)
        c.state.step = c
    }

    fun <inferFrom T> yieldAll(values: Sequence<T>, continuation c: Continuation<Unit, LazySequenceImpl<T>>) {
        c.state.addValues(values)
        c.state.step = c
    }
    
    operator fun resultHandler(r: Unit, c: CompletedCoroutine<LazySequenceImpl<T>>) {
        c.state.completed = true        
    }
}

class LazySequenceImpl<T> : Sequence<T> {
    ...
    
    fun advanceIfNeeded() {
        if (!advancedSinceLastYield) step.run(Unit)
    }
    
    override fun iterator() = object : Iterator<T> {
        override fun hasNext(): Boolean {
            advanceIfNeeded()
            return !completed
        }
        
        override fun next(): T {
            advanceIfNeeded()
            if (completed) throw NoSuchElementException()
            return getNextValue()
        }
    }
    
    ...
}
```

The type checker looks at calls to `yield` and `yieldAll` in the body of the coroutine and creates typing constraints 
for the `Coroutine` parameter of the builder function `generate`, thus deducing that its `T` is `Int` in the example 
above.

## Exception handling
 
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
 
 
 
 
 
Use case for extensions as suspension points: web server 
 
 
 
 
   
 
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
    
 
 
 
 
 
 
 
 
 
 
 
 

Let's say that for this example the calls to `await` are suspension points, this means that the computation can be 
suspended when we need to read from a file, so that the read may not block the current thread, but execute asynchronously,
and when it's done the coroutine can be resumed. Later it will suspend until each write is completed, and resume then.

While this is not how we plan to implement coroutines, a useful mental model is related to the _continuation passing style_
or CPS. In this style, at every suspension point, the rest of the coroutine is passed as a callback to the asynchronous 
computations the coroutine runs:
   
``` kotlin
inFile.readData {
    input ->
    val processed = process(input)  
    outFile.write(processed,
      callback = {
        outFile.closeSafely()      
      })        
}
```
   


Another way of thinking about coroutines 
is that every _suspension point_ have a _continuation_ available as an

## Issues

- how to unify async and generators in a typed language? The type of yield'ed values must be the same for generators, but not for async.
- can transformed lambdas have their own return values?
- keyword-less vs keyword-based syntax
- handling of `finally` blocks
- stack spilling for normal calls
- stack spilling for constructor calls (mind the class initialization issue, check otehr languages)
- irreducible CFGs may degrade performance
- volatile fields to store state
- nullify fields referencing objects when corresponding local go out of scope (to prevent memory leaks)

## Requirements

- No dependency on a particular implementation of Futures or other such rich library
- Cover equally the "async/await" use case and "generator blocks"
- Good for wrapping existing asynchronous APIs, such as JDK Futures and those shipping with Quazar

## Examples

Examples below use the keyword-based version of the syntax. This is only one version, may be changed later. Keywords are: `cofun` and `yield`

### Generators

Usage:

``` kotlin
fun seq() = generator {
	println("a")
	yield 1
	println("b")
	yield 2
	println("c") // not called unless someone calls hasNext() after last next()
}

// This will work:
for (i in seq()) println(i)
// prints a 1 b 2 c

// This will work
val it = seq().iterator()
println(it.next()) // prints a 1
println(it.next()) // prints b  2
it.hasNext() // pritns c

// This will work
val it = seq().iterator()
it.hasNext() // prints a
println(it.next()) // prints 1
it.hasNext() // prints b
println(it.next()) // prints  2
it.hasNext() // pritns c
```

Code transformed as follows:

``` kotlin
fun seq() = generator(Coroutine$1())

class Coroutine$1() : SimpleCoroutineYielding<Int>() {
	private var label = 0

	override fun nextStep(exception: Throwable? = null) {
		when (label) {
			0 -> {
				assert(exception != null, "An exception is passed, but it couldn't have occurred anywhere")
				println("a")
				// yield 1
				label = 1
				hasLastStepYielded = true
				lastYieldedValue = 1
				return
			}
			1 -> {
				if (exception != null) throw exception
				println("b")
				// yield 2
				label = 2
				hasLastStepYielded = true
				lastYieldedValue = 2
				return
			}
			2 -> {
				println("c")
				label = 3
				hasLastStepYielded = false
				return
			}
			3 -> throw CoroutineEndedException()
		}
	}
}

```

Declarations:

``` kotlin
yielding fun <T> generator(d: SimpleCoroutineYielding<T>): Sequence<T> {
	return object : Iterator<T> {
		private var valueReturned = true
		private fun advanceIfNeeded() {
			if (valueReturned) d.nextStep()
		}

		override fun next(): T {
			advanceIfNeeded()
			if (!hasNext()) throw NoSuchElementException()
			valueReturned = true
			return d.lastYieldedValue
		}

		override fun hasNext(): Boolean {
			advanceIfNeeded()
			return d.hasLastStepYielded
		}
	}.asSequence()
}
```

## Overview of solutions/proposals in other languages

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
