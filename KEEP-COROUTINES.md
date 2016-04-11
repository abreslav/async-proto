# KEEP-COROUTINES. Coroutines for Kotlin

Document type: Language Design Proposal
Document authors: Andrey Breslav, Vladimir Reshetnikov
Other contributors: Stanislav Erokhin, Ilya Ryzhenkov
Document status: under review
Prototype implementation: not started  

## Abstract

We propose to add coroutines to Kotlin. This concept is also known as or partly covers

- generators/yield
- async/await
- (delimited or stackless) continuations

## Use cases

Coroutine can be thought of as a _suspendable computation_, the one that can suspend at some points and later continue 
(possibly on another thread). Coroutines calling each other (and passing data back and forth) can form the machinery for 
cooperative multitasking, but this is not exactly the driving use case for us.
 
### Asynchronous computations 
 
First motivating use case for coroutines is asynchronous computations (handled by async/await in C# and other languages). 
Let's take a look at how such computations are done with callbacks. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified, to make examples shorter, we use named arguments to make the code more
self-explanatory):

```
inFile.read(into = buf, whenDone = {
    bytesRead ->
    ...
    ...
    val newData = process(buf, bytesRead)
    outFile.write(from = buf, whenDone = {
        ...
        ...
        outFile.close()          
    })
})
```

Note that we have a callback inside a callback here, and while it saves us from a lot of boilerplate (e.g. there's no 
need to pass the `buf` parameter explicitly to callbacks, they just see it as part of their closure), the indentation
levels are growing every time, and one can easily anticipate the problems that may come at nesting levels greater than one 
(google for "callback hell" to see how much people suffer from this in current JavaScript, where they have no choice 
other than use callback-based APIs).

This same computation can be expressed straightforwardly as a coroutine (provided that there's a library that adapts
 the I/O APIs to coroutine requirements):
 
```
asyncIO {
    val bytesRead = inFile.read(into = buf) // suspension point
    ...
    ...
    val newData = process(buf, bytesRead)
    outFile.write(from = buf) // suspension point
    ...
    ...
    outFile.close()
}
```

If we assume that every _suspension point_ (such points are to be statically determined at compile time) implicitly
receives as an argument a callback enclosing the entire _continuation_ of the `asyncIO` coroutine, we can see that this is 
the same code as above, but written in a more readable way. NOTE: passing continuation lambdas around is not exactly 
how we are proposing to implement coroutines, it's just a useful mental model. 

Note that in the callback-passing style having an asynchronous call in the middle of a loop can be tricky, and in a
coroutine a suspension point in a loop is a perfectly normal thing to have:

```
asyncIO {
    while (true) {
        val bytesRead = inFile.read(into = buf) // suspension point
        if (bytesRead == -1) break
        ...
        val newData = process(buf, bytesRead)
        outFile.write(from = buf) // suspension point
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

This could be rewritten as

```
asyncFutures {
    val original = asyncLoadImage("...original...") // creates a Future
    val overlay = asyncLoadImage("...overlay...")   // creates a Future
    ...
    return applyOverlay(await(original), await(overlay))
}
```

Again, less indentation and more natural composition logic (and exception handling, not shown here). For more complex
logic the difference between the coroutine code and futures-based code becomes much more dramatic.

> With the help of _delegated properties_, the example above may be simplified even further:
```
asyncFutures {
    val original by asyncLoadImage("...original...")
    val overlay by asyncLoadImage("...overlay...")
    ...
    // access to the properties (i.e. the getValue() function) is a suspension point,
    // so there's no need for explicit await()
    return applyOverlay(original, overlay)
}
```

### Generators

Another typical use case for coroutines would be lazily computed sequences of values (handled by `yield` in C#, Python 
and many other languages):
 
```
val seq = input.filter { it.isValid() }.map { it.toFoo() }.filter { it.isGood() }
```

This style of expressing (lazy) collection filtering/mapping is often acceptable, but has its drawbacks:

 - `it` is not always fine for a name, and a meaningful name has to be repeated in each lambda
 - multiple intermediate objects created
 - non-trivial control flow and exception handling are a challenge
 
As a coroutine, this becomes close to a "comprehension":
 
```
val seq = input.transform {
    if (it.isValid()) {      // "filter"
        val foo = it.toFoo() // "map"
        if (foo.isGood()) {  // "filter"
            yield(foo) // suspension point        
        }                
    }
} 
```

This form may look more verbose in this case, but if we add some more code in between the operations, or some non-trivial
control flow, it has invaluable benefits:

```
val seq = transform {
    yield(firstItem)

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

This approach also allows to express `yieldAll(sequence)`, which simplifies joining lazy sequences and allows for 
efficient implementation (a naïve one is quadratic in the depth of the joins).  

Some other use cases:
 
* UI logic involving off-loading long tasks from the event thread
* Background processes occasionally requiring user interaction, e.g., show a modal dialog
* Communication protocols: implement each actor as a sequence rather than a state machine
* Web application workflows: register a user, validate email, log them in (a suspended coroutine may be serialized and stored in a DB)
   




## Coroutines overview

This section gives a brid's-eye view of the proposed language mechanisms that enable writing coroutines and libraries that govern their semantics.  

### Terminology

* A _coroutine_ -- a block of code (possibly, parameterized) whose execution can be suspended and resumed potentially multiple times (possibly, at several different points), yielding the control to its caller. [Note: The wording "potentially multiple times" should be understood as "zero, one or more times". While it is rarely useful, a coroutine can we written in a way such that it is never suspended at all. End note]. 

Syntactically, a coroutine looks exactly as a function literal `{ x, y -> ... }`. A coroutine is distinguished by the compiler from a function literal based on the special type context in which it occurs. A coroutine is typechecked using different rules it in a different way than a regular function literal.
 
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

As mentioned above, implementing continuations in coroutines as lambdas makes certain scenarios (suspending in a loop, handling exceptions, etc) difficult. This is why many languages implement them through _state machines_.  
 
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
 
The code is compiled to an anonymous class that has a method implementing the state machine, a field holding the current
 state of the state machine, and fields for local variables of the coroutines that are shared between states. Here's pseudo-bytecode for the coroutine above: 
  
```
class <anonymous_for_state_machine> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void main(Object data) {
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

Now, when the coroutine is started, we call its `main()`: `label` is `0`, and we jump to `L0`, then we do some work, 
set the `label` to the next state — `1` and return (which is — suspend the execution of the coroutine). 
When we want to continue the execution, we call `main()` again, and now it proceeds right to `L1`, does some work, sets
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
    
    void main(Object data) {
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

Note: boxing can be eliminated here, through having another parameter to `main()`, but we are not getting into these details.

## The building blocks

One of the driving requirements for this proposal is flexibility: we want to be able to support many existing asynchronous
APIs and other use cases (unlike, for example, C#, where async/await and generators are tied up to Task and IEnumerable).

To achieve this, we provide more direct access to the state machine, and introduce building blocks that frameworks and
libraries can use: _coroutine builders_, _suspending functions_ and _controllers_.

NOTE: all names, APIs and syntactic constructs described below are subject to discussion and possible change.

### Coroutine builders

_Coroutine builders_ are functions that take state machines and turn them into some useful objects like Futures, 
Observables, lazy Sequences etc:
   
```
val f: Future<T> = asyncExample { 
    // coroutine body
}
```

Here, `asyncExample` is a function that receives a block which is a body of a coroutine ("colambda") as an argument. 
Under the hoods this block is translated into a state machine, and the parameter type for `asyncExample` is not 
a function type (e.g. `() -> Unit`), but an interface `Coroutine` that exposes functions to initialize the coroutine, 
start its execution, etc:
 
```
fun <T> asyncExample(coroutine: Coroutine<...>): Future<T> { ... }
```

The job of `asyncExample` is to wrap the coroutine into a `Future` object (those who are into GoF design patterns may say
 that it's an "Adaptor Factory Method"): for example, it may create a promise (`CompletableFuture`) and set a completion
 handler on the coroutine to fulfill it (i.e. call `CompletableFuture.complete()`). 
 
This essentially proposes a change to the language syntax: previously, when we saw a lambda expression, we 
expected the type of it (e.g. a type of a parameter it is passed for or a property/variable it is assigned to) to be a
function type, e.g. `(Int) -> String`, but now it can also be a `Coroutine<...>`. So this interface is a special
type in the sense that the compiler knows about it and transforms the lambdas passed to such parameters to state machines.

* It is open to discussion whether we should have a special syntax for `Coroutine` (like we have for function types), or
  leave them is the generic form.
 
NOTE: Technically, one could implement the `Coroutine` interface and pass a custom implementation to `asyncExample`, in the 
same manner as it can be done with functions. And, of course, a function that takes a `Coroutine` doesn't have to really
build anything, so _coroutine builder_ is not a syntactical property, or kind of functions the language knows about, but
simply a coding pattern.
 
### Suspension points

To recap: a _suspension point_ is an expression in the body of a coroutine which cause the coroutine's execution to
suspend until it's explicitly resumed by someone.
  
An example of a suspension point is `await()`, that takes an asynchronous computation (e.g. a `CompletableFuture`) and
suspends until it's completed. Technically, it could subscribe for completion of the Future with a handler that resumes
the coroutine when called. In fact, it needs to do a bit more, because there's the result computed by the Future, which
needs to be put back "into" the coroutine:

```
asyncExample {
    val x = await(foo) // suspension point
    println(x)
}
```

The state machine for this example will look something like this:

```
L0:
  label = 1
  await(foo, this)
  return
L1:
  println(x)
  return
```

The code after `L1` assumes that someone has already written a value computed by `foo` into `x` (which is a field in the
anonymous class of the state machine). The way it works is roughly as follows:
 
```
fun <T> await(f: CompletableFuture<T>, continuation c: Continuation<T, ...>) {
    f.whenComplete { t, throwable ->
        if (throwable != null) 
            c.runWithException(throwable)
        else          
            c.run(t)
    }
}
``` 

Here, we have another new piece of syntax: the `continuation` modifier on the second parameter `c` (that is not passed
explicitly to `await()` in the code); and a new interface: `Continuation` that can either run with a given value, or
run with exception (we'll not delve into the exception handling just yet, although it's a very important and interesting 
topic). The `T` type-argument of a continuation is the type that `await` "returns" in the coroutine. Since the result is 
received asynchronously, it can not be returned directly from await, and is passed to the continuation as an argument to
`run`.

So, a suspension point is a function call with an implicit continuation parameter. Such functions can be called in this
 form only inside coroutines. Elsewhere they may be called only with both parameters passed explicitly.
  
NOTE: some may argue that it's better to make suspension points more visible at the call sites, e.g. prefix them with
  some keyword or symbol: `#await(foo)` or `suspend await(foo)`, or something. These options are all open for discussion.

But where does the implementation of `Continuation` come from, and what is its `run` function actually doing? 
The `run` function does two things: 

- runs the state machine's `main()` so that it proceeds with the execution of the coroutine, 
- passes a value of the local variable `x` to it (the cheapest way of implementing this is 
  through a parameter passed to `main()`).
 
NOTE: even if the result of `await()` is not assigned to a variable in the code of the coroutine, such a variable may be
 created by the compiler, or some other mechanism of putting a value into the coroutine through a field may be employed 
 (e.g. we could use one field for all suspension point results, or have one field for reference-typed results and another
 for primitives to avoid boxing, because any primitive can be encoded as a `Long`).
 
To achieve this, the compiler needs to either generate a new class that implements `Continuation` and has the appropriate
 `run` function implementation, or make the state machine itself implement `Continuation`, which would mean fewer classes
 and allocations, and thus is the option we'd prefer.   

### Controllers

The model presented above is rather flexible: anyone can declare a coroutine builder or a suspension point independently.
This flexibility may need some governance to avoid chaos, plus for many use cases there's a need for some kind of
"execution context": a party that knows, for example, what thread pool to schedule computations on, what time-outs to set,
how to handle errors and so on. Plus, there are some type-checking issues, we'll cover below. To address all these concerns
we introduce _controller objects_.

One can think of a _controller object_ as an [implicit receiver](https://kotlinlang.org/docs/reference/extensions.html#declaring-extensions-as-members) 
available inside a coroutine, because it's members are available in the body of coroutine without explicit qualification. 
In fact, most suspension points are either members of or extensions to a controller, so they have access to the execution 
context and preferences. 

In fact, every coroutine must have a controller set (normally by its builder) before it is executed, this is done through
calling some function on the `Coroutine` object that the builder receives. 

* It's open to discussion whether we should allow calling suspension points which are not members/extensions to the 
current controller, or there may be "free-floating" suspension points.  

Note that normally controllers are singletons or at least very long-lived objects, so the need to allocate them does not
impose significant performance penalties.

What can controllers do?
 * define behavior at suspension points,
 * define exception handlers,
 * define handlers for return from the coroutine (e.g. the last expression in the code block, or explicit `return`'s),
 * (maybe something else?)
 
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
