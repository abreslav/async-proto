# Coroutines for Kotlin

* **Type**: Informal description
* **Author**: Andrey Breslav
* **Contributors**: Vladimir Reshetnikov, Stanislav Erokhin, Ilya Ryzhenkov
* **Status**: Under consideration
* **Prototype**: not started  

## Abstract

We propose to add coroutines to Kotlin. This concept is also known as, or partly covers

- generators/yield
- async/await
- (delimited or stackless) continuations

Requirements:
- No dependency on a particular implementation of Futures or other such rich library;
- Cover equally the "async/await" use case and "generator blocks".

It is an explicit goal of this proposal to make it possible to utilize Kotlin coroutines as wrappers for different existing asynchronous APIs (such as Java NIO, different implementations of Futures, etc).  

## Table of Contents

* [Use cases](#use-cases)
  * [Asynchronous computations](#asynchronous-computations)
  * [Futures](#futures)
  * [Generators](#generators)
  * [More use cases](#more-use-cases)
* [Coroutines overview](#coroutines-overview)
  * [Terminology](#terminology)
  * [Implementation through state machines](#implementation-through-state-machines)
* [The building blocks](#the-building-blocks)
  * [A lifecycle of a coroutine](#a-lifecycle-of-a-coroutine)
  * [Library interfaces](#library-interfaces)
  * [Controller](#controller)
  * [Suspending functions](#suspending-functions)
  * [Result handlers](#result-handlers)
  * [Exception handlers](#exception-handlers)
  * [Continuing with exception](#continuing-with-exception)
* [Type-checking coroutines](#type-checking-coroutines)
* [Code examples](#code-examples)

## Use cases

A coroutine can be thought of as a _suspendable computation_, i.e. the one that can suspend at some points and later continue (possibly on another thread). Coroutines calling each other (and passing data back and forth) can form the machinery for cooperative multitasking, but this is not exactly the driving use case for us.
 
### Asynchronous computations 
 
First motivating use case for coroutines is asynchronous computations (handled by async/await in C# and other languages). Let's take a look at how such computations are done with callbacks. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified):

``` kotlin
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
 
``` kotlin
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

> Find the library code for `asyncIO {}` [here](#a-builder-and-controller-for-asyncio).

Note that with explicitly passed callbacks having an asynchronous call in the middle of a loop can be tricky, but in a coroutine it is a perfectly normal thing to have:

``` kotlin
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

### Futures

There's another style of expressing asynchronous computations: through futures (and their close relatives — promises).
We'll use an imaginary API here, to apply an overlay to an image:

``` kotlin
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

``` kotlin
async {
    val original = asyncLoadImage("...original...") // creates a Future
    val overlay = asyncLoadImage("...overlay...")   // creates a Future
    ...
    // suspend while awaiting the loading of the images
    // then run `applyOverlay(...)` when they are both loaded
    return applyOverlay(await(original), await(overlay))
}
```

> Find the library code for `async {}` [here](#a-builder-and-controller-for-asyncawait)  

Again, less indentation and more natural composition logic (and exception handling, not shown here), and no building async/await into the language: `async{}` and `await()` are functions in a library. 

### Generators

Another typical use case for coroutines would be lazily computed sequences (handled by `yield` in C#, Python 
and many other languages):
 
``` kotlin
val seq = input.filter { it.isValid() }.map { it.toFoo() }.filter { it.isGood() }
```

This style of expressing (lazy) filtering/mapping is often acceptable, but has its drawbacks:

 - `it` is not always fine for a name, and a meaningful name has to be repeated in each lambda,
 - multiple intermediate objects created,
 - non-trivial control flow and exception handling are a challenge.
 
As a coroutine, this becomes close to a "comprehension":
 
``` kotlin
val seq = input.generate {
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

``` kotlin
val seq = generate {
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

> Find the library code for `generate {}` [here](#a-builder-and-controller-for-yield)  

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
 ``` kotlin
 fun asyncTask() = async { ... }
 ```

* _Suspension point_ -- a special expression in a coroutine that designates a point where the execution of the coroutine is suspended. Syntactically, a suspension point looks as an invocation of a function that's marked with a special modifier on the declaration site (other syntactic options may be considered at some point). 

* Such a function is called a _suspending function_, it receives a _continuation_ object as an argument which is passed implicitly from the calling coroutine.

* _Continuation_ is like a function that begins right after one of the suspension points of a coroutine. For example:
``` kotlin
generate {
    for (i in 1..10) yield(i * i)
    println("over")
}  
```  

Here, every time the coroutine is suspended at a call to `yield()`, _the rest of its execution_ is represented as a continuation, so we create 10 continuations: first runs the loop with `i = 2` and suspends, second runs the loop with `i = 3` and suspends, etc, the last one prints "over" and exits the coroutine. 

### Implementation through state machines

It's crucial to implement continuations efficiently, i.e. create as few classes and objects as possible. Many languages implement them through _state machines_, and in the case of Kotlin this approach results in the compiler creating only one class and one instance per coroutine.   
 
Main idea: a coroutine is compiled to a state machine, where states correspond to suspension points. Example: let's take a coroutine with two suspension points:
 
``` kotlin
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
  
``` java
class <anonymous_for_state_machine> implements Coroutine<...> {
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
set the `label` to the next state — `1`, call `await()` and return (which is — suspend the execution of the coroutine). 
When we want to continue the execution, we call `resume()` again, and now it proceeds right to `L1`, does some work, sets
the state to `2`, calls `await()` and suspends again. Next time it continues from `L3` setting the state to `-1` which means "over, 
no more work to do". The details about how the `data` parameter works are given below.

A suspension point inside a loop generates only one state, because loops also work through (conditional) `goto`:
 
``` kotlin
var x = 0
while (x < 10) {
    x += await(nextNumber())
}
```

is generated as

``` java
class <anonymous_for_state_machine> implements Coroutine<...> {
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

> Find more state machine examples in [async.kt](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/async.kt#L60) and [yield.kt](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/yield.kt#L58)  

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

> Find the library code [here](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/coroutines.kt)

So, a typical coroutine builder would look like this:
 
``` kotlin
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
 
> Find a working example [here](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/async.kt#L30)  

The `c` parameter normally receives a lambda, and its `coroutine` modifier indicates that this lambda is a coroutine, so its body has to be translated into a state machine. Note that such a lambda may have parameters which can be naturally expressed as `(Foo, Bar) -> Coroutine<...>`.  
  
The usual workflow of a builder is to first pass the user-defined parameters to the coroutine. In our example there're no parameters, and this amounts to calling `c()` which returns a `Coroutine` instance. Then, we create a controller and pass it to the `entryPoint()` method of a the `Coroutine`, to obtain the first `Continuation` object whose `resume()` starts the execution of the coroutine. (Passing `Unit` to `resume()` may look weird, but it will be explained below.)    

NOTE: Technically, one could implement the `Coroutine` interface and pass a lambda returning that custom implementation to `async`. 

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
  
``` kotlin
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

> Find a working example [here](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/async.kt#L42)  

The `suspend` modifier indicates that this function is special, and its calls are suspension points that correspond to states of a state machine. 

When `await(f)` is called in the body of the coroutine, the second parameter (a continuation) is not passed explicitly, but is injected by the compiler. After `await()` returns, the coroutine is suspended and the control is transferred to its caller. The execution of the coroutine is resumed only when the future `f` is completed, and `resume()` is called on the continuation `c` (as per the handler registered with the `CompletableFuture` object).
 
The value passed to `resume()` acts as the **return value** of `await()` calls in the body of the coroutine. Since this value will only be known when the future is completed, `await()` can not return it right away, and the return type in _any_ suspending function declaration is always `Unit`. When the coroutine is resumed, the result of the last suspending call is passed as a parameter to the `resume()` of the continuation. (This is why the `entryPoint()` gives a `Continuation<Unit>` — there's no call to return a result for, so we simply pass a placeholder object.)  

Consider this example of a coroutine:
 
``` kotlin
async {
    val x = await(foo) // suspension point
    println(x)
}
```

Here's the state machine code generated for it:
 
``` java
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
 
``` kotlin
class FutureController<T> {
    val future: CompletableFuture<T> = ...
    
    operator fun handleResult(t: T, c: Continuation<Nothing>) {
        future.complete(t)    
    }
} 
``` 
 
The `handleResult()` function is called on the last expression in the body of a coroutine as well as on each explicit `return` from it:
   
``` kotlin
val r = async {
    if (...) 
        return@async default // this calls `handleResult(default)`
    val f = await(foo)
    f + 1 // this calls `handleResult(f + 1)`           
}   
```   
 
As any function, `handleResult()` may be overloaded, and if a suitable overload is not available for a returned expression, it is a compilation error. If no `handleResult()` is defined in the controller, the values of last expression in the body of the coroutine is discarded and `return` with an argument is forbidden (`return Unit` may be allowed).
 
Note: the continuation parameter in the result handler is provided for uniformity, and may be used for advanced operations such as resetting the state machine or serializing its state. 
 
### Exception handlers
 
Handling exceptions in coroutines may be tricky, and some details of it are to be refined later, but the basics are as follows.
 
A controller may define an exception handler:
 
``` kotlin
operator fun handleException(e: Throwable, c: Continuation<Nothing>) {
    future.completeExceptionally(e) 
}
```

This handler is called when an unhandled exception occurs in the coroutine (the coroutine itself becomes invalid then and can not be resumed any more). Technically, it is implemented by wrapping the whole body of the coroutine (the whole state machine) into a try/catch block whose catch calls the handler:
  
``` java
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
 
``` kotlin
fun resumeWithException(exception: Throwable)
```
 
If a controller calls this function, the exception passed to it is re-thrown right after the coroutine is resumed (and thus it behaves as if the suspending call has thrown it):
 
``` kotlin
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

``` java
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

The type checker determines that a lambda is a body of coroutine when it's passed as an argument to a parameter marked with the `coroutine` modifier (see coroutine builder examples above). 

The following rules apply:
* only function and constructor parameters may be marked with the `coroutine` modifier,
* a parameter so marked must have a function type with arbitrary number of parameters declared (including extension function types),
* the return type of such a function type must be `Coroutine<...>` where the type-argument is called a _controller types_,
* the controller type may be any type at all.

In the body of a coroutine, member functions of the controller that are marked with the `suspend` modifier may be called without qualification and without passing the continuation parameter explicitly. The controller itself is not accessible through `this`. (We may allow other kinds of functions on controller to be called without qualification.)
   
A function may be marked as `suspend` (and thus be a _suspending function_) if 
* it has at least one parameter, 
* the last of its parameters (the _continuation parameter_) is not vararg, and
* has type `Continuation<...>` where the type-argument is called the _result type_ of the suspending function.
    
Suspending functions can not be called from noinline and crossiniline lambdas or locals classes, or anonymous objects in the body of the coroutine. Essentially, they can only be called from the same places where a `return` expression may occur for the coroutine.
      
Suspending function can not be called from `finally` blocks (this limitation may be lifted later).
      
A suspending call is type-checked assuming that the there's an argument implicitly supplied for the continuation parameter, and it is an expression of type `Continuation<R>`. `R` is also considered the return type for such a call, and the actual type for type-variable `R` is determined through type inference, including the expected type constraints.

In case of a suspending function mentioning type parameters of the controller class in its signature, the type inference algorithm can sometimes determine these type parameters, which may affect the type inference on the builder function.

> A typical example of this is generators, where the `generate` function has a type-parameter `T` and returns `Sequence<T>`, and this `T` is determined based on what values calls to `yield()` take in the body of the coroutine.  

# Code examples
  
See this repo for complete samples: [kotlin-coroutines-examples](https://github.com/abreslav/kotlin-coroutines-examples/tree/master/src).  
 
#### A builder and controller for async/await
 
``` kotlin
// Note: this code is optimized for readability, the actual implementation would create fewer objects

fun <T> async(coroutine c: () -> Coroutine<FutureController<T>>): CompletableFuture<T> {
    val controller = FutureController<T>()
    c().entryPoint(controller).resume(Unit)
    return controller.future
}

class FutureController<T> {
    val future = CompletableFuture<T>()

    suspend fun <V> await(future: CompletableFuture<V>, machine: Continuation<V>) {
        future.whenComplete { value, throwable ->
            if (throwable == null)
                machine.resume(value)
            else
                machine.resumeWithException(throwable)
        }
    }

    operator fun handleResult(value: T, c: Continuation<Nothing>) {
        future.complete(value)
    }

    operator fun handleException(t: Throwable, c: Continuation<Nothing>) {
        future.completeExceptionally(t)
    }
}
``` 

> See the [repo](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/async.kt#L30) 
 
#### A builder and controller for yield

``` kotlin
// Note: this code is optimized for readability, the actual implementation would create fewer objects

fun <T> generate(coroutine c: () -> Coroutine<GeneratorController<T>>): Sequence<T> = object : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val iterator = GeneratorController<T>()
        iterator.setNextStep(c().entryPoint(iterator))
        return iterator
    }
}

class GeneratorController<T>() : AbstractIterator<T>() {
    private lateinit var nextStep: Continuation<Unit>

    override fun computeNext() {
        nextStep.resume(Unit)
    }

    fun setNextStep(step: Continuation<Unit>) {
        this.nextStep = step
    }


    suspend fun yieldValue(value: T, c: Continuation<Unit>) {
        setNext(value)
        setNextStep(c)
    }

    operator fun handleResult(result: Unit, c: Continuation<Nothing>) {
        done()
    }
} 
``` 

> See the [repo](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/yield.kt#L25)

This makes use of the [`AbstractIterator`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-abstract-iterator/) class from the standard library (find its source code [here](https://github.com/JetBrains/kotlin/blob/dd2ae155315a5c100daaad515068075ce02c99f4/libraries/stdlib/src/kotlin/collections/AbstractIterator.kt#L12)). 

 
#### A builder and controller for asyncIO
 
``` kotlin
// Note: this code is optimized for readability, the actual implementation would create fewer objects

fun <T> asyncIO(coroutine c: () -> Coroutine<AsyncIOController<T>>): CompletableFuture<T> {
    val controller = AsyncIOController<T>()
    c().entryPoint(controller).resume(Unit)
    return controller.future
}

class AsyncIOController<T> {
    val future = CompletableFuture<T>()

    private class AsyncIOHandler(val c: Continuation<Int>) : CompletionHandler<Int, Nothing?> {
        override fun completed(result: Int, attachment: Nothing?) {
            c.resume(result)
        }

        override fun failed(exc: Throwable, attachment: Nothing?) {
            c.resumeWithException(exc)
        }
    }

    suspend fun AsynchronousFileChannel.aRead(buf: ByteBuffer, position: Long, c: Continuation<Int>) {
        this.read(buf, position, null, AsyncIOHandler(c))
    }

    suspend fun AsynchronousFileChannel.aWrite(buf: ByteBuffer, position: Long, c: Continuation<Int>) {
        this.write(buf, position, null, AsyncIOHandler(c))
    }

    operator fun handleResult(value: T, c: Continuation<Nothing>) {
        future.complete(value)
    }

    operator fun handleException(t: Throwable, c: Continuation<Nothing>) {
        future.completeExceptionally(t)
    }
}
``` 

> See the [repo](https://github.com/abreslav/kotlin-coroutines-examples/blob/master/src/io.kt#L100)
