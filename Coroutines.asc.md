PROPOSAL DRAFT: ADDING COROUTINES TO KOTLIN
===========================================

This is a draft of the proposal for adding coroutine support to the Kotlin language (http://kotlinlang.org).
------------------------------------------------------------------------------------------------------------

Introduction
============

Let's examine a normal "lifetime" of a method invocation. Usually, an execution of a particular method always starts at a single entry point, continues without interruption, following the control flow path (or one of serveral possible control flow path, with branches selected depending on some conditions) until it reaches one of its exit points, where the method returns some return value and control to its immediate caller, at which moment the execution of the method is completed. The set of parameters and local variables specific to this particular invocation of the method (usually referred to as a stack frame) ceases to exist at that moment, unless some of them have been captured in a closure that outlives the method invocation. The mechanism of exceptions (that can be seen as a simple form of non-local control transfer), introduces some minor modifications into this process.

In some scenarios it is desirable to have a more involved behavior than the one just described. It may be necessary to suspend an execution of a method at a certain expression(s) in its body, and transfer the control flow elsewhere (usually with some additional data). The execution of the method is not yet complete at this point, and the set of its parameters and local variables is preserved, so that the execution of the method can be resumed at some later moment at exactly the same point where it has been suspended. A possible motivation for this case is that rather than computing and returning the whole return value (e.g. a list) at once, parts of that value can become known earlier, and it is desirable to make them available to the calling code immediately, without waiting for the completion of the method (this is a typical scenario when implementing generators or iterators). Another motivation is to start execution of a method immediately when it can be scheduled by the execution environment (OS, VM, etc.), when not all data required by the method is available yet. The missing data can be represented by an object encapsulating a delayed computation, that is usually known as a task, future or promise. When the method execution reaches a point when the missing data is required to continue execution, but it is not yet available, the method execution is suspended until the data becomes available. It is usually said that the task/future/promise is awaited at this point. A functional value representing the continuation of the suspended method can be subscribed to be invoked when the task/future/promise is completed.

So languages provide a predefined set of language-supported coroutine kinds with mostly fixed behavior, tied to certain predefined types. A method implementing a coroutine is converted to a state machine, whose states correspond to points in the method body where the execution of the method can be suspended. The state machine is an object (usually of an anonymous compiler-generated class) allocated on the heap (so, its lifetime is not restricted by a lifetime of a certain stack frame). Parameters and local variables of the method become fields of the object reperesing the state machine, and the method body is encapsulated (with some pertinent transformation) into one (or sometimes more) methods of the object. The object usually has an additional field storing a sequential number of the current state. An invocation of the coroutine usually returns an instance of a predefined class or an interface, representing the coroutine and providing some methods to observe or control its current state, subscribe to its completion or combine it with other objects of the same or similar type. The actual invocation of the coroutine may have already stated prior to returning the result (as often happens with futures), or may have to be manually started by invocation of a certain method (as often happens with lazy generators). So a language prescribes both how a continuation is transformed into a state machine, and how a public API of the state machine looks and behaves. In our view, this approach limits the scope of those features, and restricts their applicability and usefulness. If an asynchronous lazy generator is required in a certain scenraio, it has to wait until this particular kind of coroutines is implemented in the language (or, more probably, has to be manually written using lower-level language constructs, that is both error-prone and obscuring the idea the programmer has in mind). Apparently, it also increases burden on compiler developers, who need to design, implement and test support for every new kind of coroutines in the language.

We propose an alternative approach, where the compiler is only controls the transformation of the coroutine body ito a state machine, why its public API and precise behavior can be defined in a library. So, new kind of coroutines can easily be introduced by using or creating new libraries, without any changes to the language.

Here is a summary of our proposal. Some details, in particular, syntax rules, are chosen quite arbitrarily and are not intended to represent a final design. For simplicity, we assume that transformation of coroutines to stack machine happens only for function literals, not named functions (this restriction can be lifted leter if deemed necessary). To distinguish function literals representing coroutines, we prefix them with an ampersand: `&{...} `. Every coroutine has an associated controller object. We write an expression (usually, a variable) that evaluates to the controller object, immediately before the coroutine: `ctrl &{...}`. The controller is responsible for governing the coroutine life cycle, and provide an API allowing other parts of the program to interact with the coroutine. The type C of the controller object is not prescribed exactly. We assume that the type C has a non-empty set of methods marked with the modifier `cofun` (typical names of those methods are `yield` and `await`). These methods are available within the coroutine body by their simple names, but with signatures different than the ones specified in their declarations. The locations in the coroutine body where any of these methods is invoked are called suspension points.

The coroutine itself is converted by the compiler into a state machine, implemented by an anonymous compiler-generated class S. The body of the coroutine, with some tranformations explained below, is converted to a method of S named `invoke` (for simplicity, we omit details of transformations of try-catch-finally statements). The parameters and local variables of the coroutine are converted to fields of the class S (unless they are captured in a nested anonymous function and so are already have to be converted to fields of a class representing a closure). The class S has an integer field named `state` storing the index of the current state of the state machine. Every evaluation of the expression `ctrl &{...}` creates a new instance of S, passes it as a single argument to the method of C called `create`, and the result of the evaluation of the expression `ctrl &{...}` is whatever value of whatever type returned by the method `create` (typically, it is a future or a lazy sequence). The controller, depending on its purposes, are free to perform the first invocation of the method `invoke` of S synchronously before returning from `create`, or at some later moment, possibly triggered by an invocation of a method in its public API (this corresponds to an immediate or delayed start of the coroutine). The method `invoke` typically has a parameter (TODO: figure out the rules about its typing), but an argument for this parameter is ignored for the first invocation of the `invoke` method. The body of the invoke method (recall that is has been constructed from the coroutine body) is enclosed in a `when` statement controlled by the `state` variable, each branch of the `when` statement represents a part of the coroutine between two consecutive suspension points. The evaluation rules for each branch are the regular rules that apply for non-coroutine blocks. A local variable that is used strictly within a single branch may be implemented as a regular local by the compiler, without promoting it to a field of the state machine. The branch corresponding to the initial state is the part of the coroutine between its entry point and its first suspension point. It executes on the first call to `invoke`, and ends when the control reaches the expression representing the first suspension point. Assume that this point has the form `val x = foo(bar(), yield(baz()), qux())` where `yield` is a `cofun` methos. The subexpressions `bar()` and `baz()` belong to the first branch, the result of `bar()` is stowed, and the result of `baz()` is used in the invocation of the `yield` method. The subexpression `val x = foo(<result of bar>, <result of yield>, qux())` belongs to the second branch (so, the second branch first unstows the result of `bar()`, then retrieves a value passed from outside to serve as the result of `yield`, then invokes `qux`, then invokes `foo` with 3 arguments on the evaluation stack and, finally, assign the result of `foo` to the local variable `x` and proceeds with the further evaluation of the second branch).
the similar process happens at the beginning of the third and other branches.

Let us inspect in more details the invocation of `yield`. This is a pseudo-function, in the sense that it syntactically looks like a regular function invocation, but is performed in a different way. Its argument(s) are passed to the corresponding parameters of the function named `yield` with the modifier `cofun` in the controller, but the return type of that declaration is `Unit` and it typically returns immediately back to the `invoke` method after storing its arguments within the controller. Then the `invoke` function returns immediately `true` to its caller (that can be `create` or another method of the controller).

Motivating scenarios
====================

yield
yield with a response
await
async yield
break/continue
maybe
producer/consumer

Terminology
===========
* An _anonymous routine_ (TODO: find simpler term?) -- an umbrella term, covering both function literals and coroutines (for the definition of coroutines, see below). The term "anonymous routine" is purely syntactical. An anonymous routine is delimited with curly braces `{...}`. The code between the curly braces is called the _content_ of the anonymous routine. The content may be empty (i.e. have no tokens). The content may optionally start with an _explicit parameter list_ (ending with the right arrow token `->`). The rest of the content is called the _body_ of the anonymous routine. The explicit parameter list may be empty (i.e. consist of the single right arrow token `->`). Alternatively, an anonymous routine may have no explicit argument list at all, in which case its content consists of its body only. [Note: A missing explicit parameter list does not necessarily mean that the anonymous routine has no parameters. If so implied by its type context, it may have a single implicit parameter called `it`. End note]

* A _coroutine_ -- a block of code (possibly, parameterized) whose execution can be suspended and resumed potentially multiple times (possibly, at several different points), yielding the control to its caller. [Note: The wording "potentially multiple times" should be understood as "zero, one or more times". While it is rarely useful, a coroutine can we written in a way such that it is never suspended at all. End note]. 

Syntactically, a coroutine looks exactly as a function literal `{ x, y -> ... }` and, indeed, coroutines and function literals are grouped together under the syntactical umbrella term "anonymous routines". A coroutine is distinguished by the compiler from a function literal based on the special type context in which it occurs. A coroutine is typechecked using different rules it in a different way than a regular function literal converts it to a state machine object.

resulting in a sequence of steps, whose local state is persistent between different steps. .   The resemblance of coroutines to function literals is purely syntactical. When the term "a function literal" is used in this specification without any additional qualification, it does NOT include coroutines. Sometimes the term "a regular function literal" is used to highlight its semantic difference from coroutines, but the qualification "regular" is strictly redundant here. There is another syntactical aspect, where coroutines can be written in a way similar to function literals. Namely, if a coroutine appears as the last argument to a function invocation, it can be specified outside of the parenthesized argument list, immediately following it (if there are no other arguments, then the empty argument list `()` can be omitted entirely). 

[Note: Some languages with coroutine support allow coroutines to take forms both of an anonymous function and of a method body. Kotlin supports only one syntactic flavor of coroutines, resembling function literals. In case where a coroutine in the form of a method body would be used in another language, in Kotlin such method would typically be a regular method with an expression body, consisting of an invocation expression whose last argument is a coroutine. End note] 

[Note: When a regular function `f` invokes another function (that, in turn, can make other invocations), the execution of `f` is, in a sense, suspended with the control flow transferred to the callee, and is resumed later, when the callee returns. The local state of `f` is preserved on stack during the nested call, and this does not require any additional machinery. This observation does not mean that every function is a coroutine. We only classify a function as a coroutine, if it is possible to preserve its local state not relying on the current call stack, suspend it execution and transfer the control flow to its caller (the caller might redirect it elsewhere). The caller typically gets ahold of a some sort of handle that allows it to resume the execution of the coroutine at a later point. End note]

* Suspension point -- a special expression in a coroutine that designates a point where the execution of the coroutine is suspended. Syntactically, a suspension point looks exactly as a function invocation. It is recognized by the compiler as a suspension point by its name, that shall match one of the special methods provided by the controller. but is evaluated in a different way than a regular function invocation. The arguments to the invocation (including the receiver expression, if any) are evaluated first, in normal left-to-right order. Then the arguments are made available to the controller, and the execution of the coroutine is suspended. When the controller resumes the coroutine, it provides a value that becomes the result of the invocation expression.

* Yielding invocation -- a special kind of a function invocation expression within a coroutine, corresponding to a suspension point of that coroutine. A yielding invocation is syntactically indistinguishable from a regular function invocation (and, indeed, is classified as a special case of an invocation expression), but is recognized by the compiler by its name, that shall match the name of a method of the controller having a special annotation (TODO: which one?). The typechecking and evaluation rules of a yielding invocation are different from those of a regular function invocation. Like any invocation expression, a yielding invocation has an argument list (that may include a receiver and a trailing anonymous routine). The argument list is evaluated first in a normal left-to-right order. Then a continuation is created, pointing to the position immediately after the yielding invocation (for example, if the yielding expression is used as an argument to an invocation, then the continuation points to the evaluation of the next argument of this invocation expression, if there is one, otherwise to the invocation itself). Then the arguments and the continuation are sent to the controller using a special method invocation (TODO: more details here). Finally, the coroutine yields control to its caller (that is typically a method of a controller), going to the suspended state.


* Local state (in a coroutine) -- the set of parameters and local variables declared within a coroutine (excluding any nested functions and coroutines), with the current set of values assigned to them. Because coroutines unlike regular functions, can be suspended with a 
* State machine -- An object of an anonymous compiler-generated class, encapsulating the behavior and state of a coroutine

* Controller -- an object governing the life cycle of the state machine representing a coroutine, and providing a higher-level API to monitor or control the execution of the coroutine. It is the controller who determines the meaning and the runtime behavior of a coroutine and yielding expressions in its body: is it a generator, an asynchronous computation, or something else. It also determines what exactly yielding expressions are available in the coroutine body.

* Step of a coroutine: part of the coroutine body either:
-- between the entry point of the coroutine and its first suspension point, or
-- between two consequtive suspension points of the coroutine, or 
-- between the last suspension point of the coroutine and its exit point.

* Task -- the object returned by a function that takes a coroutine as an argument. Typically, the function creates a controller and sets up its interation with the state machine representing the coroutine, and the task is an object encapsulating the execution of the coroutine, and exposing an API that enables to monitor or control the execution in a certain way. Typical examples of tasks are a future or a lazy sequence.

* Return value -- the result of evaluation of the last expression in a coroutine body, or the instance of the `Unit` type if the body does not end with an expression. It is up to the controller to decide how this value is used, and whether it is made available to it clients in some way.

* Continuation -- a value of a functional type pointing to a position in a coroutine immediately after one of its suspension points. A continuation for a given coroutine is said to be _current_ if the coroutine is currently suspended, and the continuation points to a position immediately after its current suspension point. A suspended coroutine is typically resumed by an invocation of its current continuation. After the coroutine has been resumed, the continuation ceases to be current, and is said to be _expired_. An invocation of an expired continuation results in an undefined behavior.

Implementation of a state machine
=================================

A state machine is an object of an anonymous compiler-generated class. It implements interfaces CofunStart and CofunStep.

```
interface CofunStart<T, F> {
	fun setController()
	val exec : F
	fun setState(...)
}


interface CofunStep<...> {
	...
}
```

A typical implementation of a controller for generator coroutines
=================================================================

Consider one of the motivating examples mentioned above -- a simple generator. [Note: This pattern is known as a "generator" in Python, and as "iterator" in C# and VB.NET. End note]. In this case a coroutine has only one kind of yielding invocations, named `yield` (although the name is not particularly important), taking a single argument of type `T` (referred to as the generator element type) and returning `Unit` (meaning that the generator does not get any data back in a response to a generated element). All yielding invocations in the single coroutine take arguments of the same type. The controller in this case is of a generic class, having `T` as its type argument -- it has to be generic to be reusable with generators of any element type. [Note: One of the goals of this design proposal is to enable automatic inference of the type `T` from arguments to `yield` invocations in this and similar scenarios. End note]

The goal of the controller in this case is to return an object implementing the interface `Sequence<T>` whose method `iterator()` returns an object implementing the interface `Iterator<T>` whose method `next()` allows to run the coroutine step-by-step. Execution of each step results in an object that is passed as an argument to `yield` and is returned to the caller of the `next()` method. The generator is suspended until the next invocation of the `next()` method, after that it proceeds to the next step.

A usage of a generator is illustrated by the following example. Suppose we have code not using coroutines:

```
fun run() {
    var sequence = object : Sequence<Long> {
        override fun iterator() = object : Iterator<Long> {
            override fun hasNext() = true
            override fun next() = System.currentTimeMillis()
        }
    }

    for(value in sequence) {
        println(value)
    }
}
```

It creates an infinite lazy sequence of return values of the function `System.currentTimeMillis()` and then iterates through the sequence using a `for` loop. This code could be rewritten using a generator coroutine as follows:

```
fun run() {
    var sequence = generate {
        while(true) {
            yield(System.currentTimeMillis())
        }
    }

    for(value in sequence) {
        println(value)
    }
}
```

The `generator` is the name of a generic function in a library (likely, in the standard library) that takes a coroutine and converts it to a `Sequence<T>` object. It has the following signature:

```
fun <T> generate(cofun body : CofunStart<GenCont, (T) -> Unit, MySeq<T>>) : Sequence<T>
```

The modifier `cofun` on its parameter `body` indicates that the corresponding argument shall be interpreted as a coroutine (rather than a function literal) and converted to a state machine. The type of the `body` parameter determines details of this conversion.

The generic interface `CofunStart` is a predefined interface that is used by the compiler to implement a state machine. Its first type-argument `GenCont` is the controller type, the second type-argument `(T) -> Unit` determines (TODO: ...), and the last type-argument `MySeq<T>` is a class that implements the interface `Sequence<T>` (it will be the runtime type of the return value of the `generate` function).

The function `generate` and the types `GetCont` and `MySeq<T>` are closely related, and are three components that are required to implement library support for generators. Typically, implementation of support of each kind of coroutines will require authoring of three such components. To use generators in their code, developers are only need to be aware of the `generate` function, and the types `GetCont` and `MySeq<T>` are mostly just implementation details. The only exception is the method `yield` defined in the `GenCont` type that is available
in the generator body, and is used to yield elements of the sequence.

`GenCont` is a singleton object.

Let us look into a possible implementation of the `generate` function:

```
fun <T> generate(cofun body : CofunStart<GenCont, (T) -> Unit, MySeq<T>>) : Sequence<T> {
	body.setController(GenCont)
	body.exec.invoke()
	body.setState()
}
```

Let us follow the control flow of this program. The first expression to evaluate is `generate { /* coroutine /* }`. This is an invocation expression with a single argument. First, the argument has to be evaluated. Its evaluation results in constructing a state machine object, that is an instance of the following compiler-generated class:

```
class StateMachine : CofunStart<...>, CofunStep<...> {
	private var state // current state of the machine
	fun step() {
		when(this.state) {
			0 ->
			1 -> 
		}
	}
}
```

```
class MySeq<T> : Sequence<T>
The evaluation of its constructor results in assignments: ....

Then, the function `generate` is invoked with the constructed state machine as its argument. The function `generate` creates a task instance. In this case it is an instance of `MySeq<Long>`. This task is returned from the `generate` function and is upcast to `Sequence<Long>`. The evaluation of the `for` loop starts with the invocation `sequence.iterator()`. The method `iterator` creates an instance of the class `MySeqIterator<Long>` that implements `Iterator<Long>`. Its constructor sets the current state of the state machine to ..., creates an instance of the functional type `...` that points to the method `...` of the state machine and stores it in the field `continuation`. Assume that the instance is assigned to a temporary local named `iter`. 

Then the evaluation of the `for` loop continues with the invocation of `iter.hasNext()`. The implementation of this method in `MySeqIterator<Long>` checks the current state of the state machine, stored in its field `state`. The value of the field is ... Because it is not ..., the `hasNext` method returns `true`. This starts the first iteration of the `for` loop. The loop variable `value` has to be assigned the result of invocation `iter.next()`. The implementation of this method in `MySeqIterator<Long>` invokes the function stored in the `continuation` field of ... The control is transferred to `step` method of the state machine. The body of this method is a `when` statement that selects the current step based on the value of `state`. In this case,  the step ... is selected. The source code of this step consists of a yielding expression `yield(System.currentTimeMillis())`. But in the implementation of the method `step()` this expression has been rewritten as an invocation of the method `yield` of the controller. First, the argument expression `System.currentTimeMillis()` is evaluated, returning some value depending on the current time. Then, the method `yield` is invoked with this argument. The implementation of the `yield` method in the `GenCont` class stores the argument to the field `...` and immediately returns (with the `Unit` return value) back to its caller -- the `step` method. The method `step` updates the value of `state` to ..., and returns (with the `Unit` return value) to its caller -- the `next` method. This method fetches the value from the field `...` (this is the value that has been passed as an argument to `yield`) and returns it to its caller -- the `run` method. The returned value is assigned to the loop variable `value` and then is passed to the `println` method. This completes the first iteration of the `for` loop.

```
run 								// user code
	StateMachine					// compiler-generated
	generate 						// library code
		MySeq<Long>					// library code
		<-- 
	/* for loop start */
	iterator 						// library code (in MySeq<Long>)
		MySeqIterator<Long> 		// library code
			(...) -> ... 			// compiler-generated
			<-- 
		<-- 
	hasNext 						// library code (in MySeqIterator<Long>)
	next 							// library code (in MySeqIterator<Long>)
		step 						// compiler-generated from user code (in StateMachine)
			currentTimeMillis 		// external
			yield 					// library code (in GenController)
			<--
		<--
	<--
	println  						// external
	/* for loop end */
```

A generator can be manipulated by obtaining an iterator for it and calling methods `hasNext()` and `next()` on the iterator. The following is an explanation of how calls to these methods result in execution of some parts of code in the generator and changing its state.

Usually, calls to `hasNext()` and `next()` are interleaved. If an invocation of `hasNext()` returned `true`, then the next invocation of `next()` shall return an element, and if `hasNext()` returned `false`, then the next invocation of `next()` shall throw `NoSuchElementException`. It follows that it is the invocation of `hasNext()` that shall trigger an execution of part of the generator body until a yielding invocation, or until the end of the body -- without the actual execution of the code, the method would have no way to decide whether there is a next element (accounting for possible loops and branching operators in the body). And this invocation results in evaluating and stashing an element to be fetched and returned later by the `next()` method.

There are some additional complications. Multiple consecuitive invocations of `hasNext()` shall be allowed, and shall return the same value, but only the first of them shall result in the execution of code in the generator. Invocations of `next()` without preceding invocations `hasNext()` also shall be allowed, and shall have the same effect as if each of them was preceded by an invocation of `hasNext()` whose return value was ignored.

Note that each invocation of the `iterator()` method on the result of the `generate` function shall create a separate instance of a state machine, that can be manipulated independently of other instances.

```
import java.util.*
import java.util.concurrent.*
import GeneratorController.State

// NOTE: Stack<T> is sometimes used below as a replacement for Option<T>, i.e. a storage that can be empty or have 1 value
// An implementation may be expected to optimize the stack away to avoid additional allocations

// ===  Library interfaces and functions ===

// === General ===

// `Parameters` - tuple of the parameters for the coroutine
// `Controller` - controller class
// `Task` - the type of an object encapsulating the execution of the coroutine, returned by functions like `generate` or `async`
interface Coroutine<Parameters, Controller, Task> {
    fun start(parameters : Parameters)
    val controller : Controller
}

interface Continuation<Argument> {
    fun run(value : Argument)
}

// Annotates a parameter whose corresponding argument shall be interpreted as a coroutine
annotation class cofun

// Annotates a method whose invocation constitutes a suspension point in a coroutine
annotation class suspension


// === Async ===

// The type of the factory parameter (`AsyncController<Result>` in this case) is used to resolve
// yielding invocations in the coroutine.
// `Result` is the return type of the coroutine
// The compiler provides a lambda of the form `{ AsyncStateMachine(it) }` to the `coroutine` parameter
fun <Result> async(
        @cofun coroutine : (AsyncController<Result>) -> Coroutine<Unit, AsyncController<Result>, CompletableFuture<Result>>)
        : CompletableFuture<Result> {
    val controller = AsyncController<Result>()
    controller.machine = coroutine(controller)

    return CompletableFuture.supplyAsync {
        controller.start()
        controller.result
    }
}

// a controller for an async coroutine
class AsyncController<Result> {
    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<Unit, AsyncController<Result>, CompletableFuture<Result>>

    fun start() {
        machine.start(Unit);
    }
    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // TODO: handle exceptions
    // This method is available in an asynchronous coroutine
    @suspension
    fun <Result> await(future : CompletableFuture<Result>, continuation : Continuation<Result>) : Unit {
        future.whenComplete { result, exception ->
            continuation.run(result)
        }
    }

    val maybeResult = Stack<Result>()

    // Every `return` expression in the coroutine corresponds to an invocation of this method
    fun returnResult(result : Result) : Unit {
        assert(maybeResult.empty())
        maybeResult.push(result)
    }

    val result : Result get() {
        if(maybeResult.empty()) throw IllegalStateException("The coroutine is not yet completed")
        return maybeResult.peek()
    }
}




// === Generators ===

// A new instance of a state machine has to be created on each invocation of `iterator()`,
// so it is passed as a factory to the `generate` method.
// The type of the factory parameter (`GeneratorController<Element>` in this case is used to resolve
// yielding invocations in the coroutine.
fun <Element> generate(
        @cofun coroutine: (GeneratorController<Element>) -> Coroutine<Unit, GeneratorController<Element>, Sequence<Element>>) =
        object : Sequence<Element> {
            override fun iterator(): Iterator<Element> {
                val controller = GeneratorController<Element>()
                controller.machine = coroutine(controller)
                return IteratorTask(controller)
            }
        }

// a controller for a generator coroutine
// `Element` is the yield type of the generator
class GeneratorController<Element> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<Unit, GeneratorController<Element>, Sequence<Element>>

    var state = State.READY

    private val maybeValue = Stack<Element>()

    private var maybeContinuation = Stack<Continuation<Unit>>()

    // This method is available in a generator in a yielding invocation
    // The identifier `yield` is currently reserved and requires escaping
    // An argument to the parameter `continuation` is provided by the compiler
    @suspension
    fun `yield`(element: Element, continuation : Continuation<Unit>) : Unit {
        assert(state == State.RUNNING)
        maybeValue.push(element)
        maybeContinuation.push(continuation)
        state = State.HAS_VALUE
    }

    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    fun returnResult(result : Unit) : Unit {
        state = State.STOPPED
    }

    fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        machine.start(Unit);
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = maybeContinuation.pop()
        continuation.run(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun fetchValue() : Element {
        assert(state == State.HAS_VALUE)
        assert(maybeValue.size == 1)
        state = State.READY
        return maybeValue.pop()
    }

    fun dropValue() : Unit {
        fetchValue() // and ignore the result
    }
}

// NOTE: There is no counterpart for this class in async coroutines, because they use standard `CompletableFuture<V>` class
// CONSIDER: possibly the task can be  merged with the controller to reduce allocations?
class IteratorTask<T>(val controller: GeneratorController<T>) : Iterator<T> {
    override fun hasNext() : Boolean {
        when(controller.state) {
            State.INITIAL -> {
                controller.start()
                return controller.state == State.HAS_VALUE
            }

            State.READY -> {
                controller.step()
                assert(controller.state == State.HAS_VALUE || controller.state == State.STOPPED)
                return controller.state == State.HAS_VALUE
            }

            State.HAS_VALUE -> {
                controller.dropValue()
                return hasNext()
            }

            State.STOPPED -> return false

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }

    override fun next(): T {
        when(controller.state) {
            State.INITIAL,
            State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext()
                return next()
            }

            State.HAS_VALUE -> return controller.fetchValue()

            State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }
}

// === Async generators ===
interface AsyncIterator<T> {
    fun hasNext() : CompletableFuture<Boolean>
    fun next() : T
}

interface AsyncSequence<T> {
    fun iterator() : AsyncIterator<T>
}

fun <Element> asyncGenerate(
        @cofun coroutine: (AsyncGeneratorController<Element>) -> Coroutine<Unit, AsyncGeneratorController<Element>, AsyncSequence<Element>>) =
        object : AsyncSequence<Element> {
            override fun iterator(): AsyncIterator<Element> {
                val controller = AsyncGeneratorController<Element>()
                controller.machine = coroutine(controller)
                return AsyncIteratorTask(controller)
            }
        }

// a controller for an async generator coroutine
// this controller provides both `yield` and `await` suspension points
// `Element` is the yield type of the generator
class AsyncGeneratorController<Element> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var machine : Coroutine<Unit, AsyncGeneratorController<Element>, AsyncSequence<Element>>

    var state = State.READY

    private val maybeValue = Stack<Element>()

    private var maybeContinuation = Stack<Continuation<Unit>>()

    // This method is available in a generator in a yielding invocation
    // An argument to the parameter `continuation` is provided by the compiler
    @suspension
    fun `yield`(element: Element, continuation : Continuation<Unit>) : Unit {
        assert(state == State.RUNNING)
        maybeValue.push(element)
        maybeContinuation.push(continuation)
        state = State.HAS_VALUE
    }

    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // TODO: handle exceptions
    // This method is available in an asynchronous coroutine
    @suspension
    fun <Result> await(future : CompletableFuture<Result>, continuation : Continuation<Result>) : Unit {
        future.whenComplete { result, exception ->
            continuation.run(result)
        }
    }
    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    fun returnResult(result : Unit) : Unit {
        state = State.STOPPED
    }

    fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        machine.start(Unit);
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = maybeContinuation.pop()
        continuation.run(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun fetchValue() : Element {
        assert(state == State.HAS_VALUE)
        assert(maybeValue.size == 1)
        state = State.READY
        return maybeValue.pop()
    }

    fun dropValue() : Unit {
        fetchValue() // and ignore the result
    }
}

class AsyncIteratorTask<T>(val controller: AsyncGeneratorController<T>) : AsyncIterator<T> {
    override fun hasNext() : CompletableFuture<Boolean> {
        when(controller.state) {
            AsyncGeneratorController.State.INITIAL -> {
                return CompletableFuture.supplyAsync {
                    controller.start()
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.READY -> {
                return CompletableFuture.supplyAsync {
                    controller.step()
                    assert(controller.state == AsyncGeneratorController.State.HAS_VALUE ||
                            controller.state == AsyncGeneratorController.State.STOPPED)
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.HAS_VALUE -> {
                controller.dropValue()
                return hasNext()
            }

            AsyncGeneratorController.State.STOPPED -> return CompletableFuture.completedFuture(false)

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }

    override fun next(): T {
        when(controller.state) {
            AsyncGeneratorController.State.INITIAL,
            AsyncGeneratorController.State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext().get() // `get()` means wait for completion
                return next()
            }

            AsyncGeneratorController.State.HAS_VALUE -> return controller.fetchValue()

            AsyncGeneratorController.State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }
}

// === Examples of usage ===

fun main(args: Array<String>) {
    val a = CompletableFuture.completedFuture("Hello")
    val b = CompletableFuture.completedFuture(42)
    run(a, b)
}

/*
// The source definition of `run`
fun run(a : CompletableFuture<Int>, b : CompletableFuture<Int>) {
    // `async` is defined in the standard library and provides a controller for async coroutines
    // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    val future : Future<String> = async {
        println("start")
        val s = await(a)
        println(s)
        val n = await(b)
        println(n)
        return@async "done"
    }

    val result = future.get(10, TimeUnit.SECONDS)
    println(result)

    var n = 0

    // `generate` is defined in the standard library and provides a controller for generator coroutines
    // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    val sequence = generate {
        println("start")
        `yield`(n++)
        println("between")
        `yield`(n)
        println("after")
        // No value is returned by a generator, the following is generated automatically
        // return@generate Unit
    }

    for (value in sequence) {
        println(value)
    }
}
*/

// The compiler-generated transformation of `run`
fun run(a : CompletableFuture<String>, b : CompletableFuture<Int>) {
    val future : Future<String> = `async` {
        AsyncStateMachine(it).apply { closure = AsyncClosure(a = a, b = b) }
    }

    val result = future.get(10, TimeUnit.SECONDS)
    println(result)

    val sequence = generate<Int> {
        GeneratorStateMachine(it).apply { closure = GeneratorClosure(n = 0) }
    }

    val tmpIterator = sequence.iterator()
    while (tmpIterator.hasNext()) {
        val value = tmpIterator.next();
        println(value)
    }
}

// compiler-generated closure
// its name is not essential and is not visible to the user
data class AsyncClosure(val a : CompletableFuture<String>, val b : CompletableFuture<Int>)


// A compiler-generated state machine
class AsyncStateMachine(
        // the type of this property is derived from the `cofun` parameter type
        override val controller: AsyncController<String>
) : Coroutine<Unit, AsyncController<String>, CompletableFuture<String>> {

    // initialized by the compiler upon creation of the state machine
    lateinit var closure : AsyncClosure

    private var state = 0

    override fun start(parameters : Unit) {
        step(null)
    }

    // The type argument `Any?` is erased and thus is not relevant, because this code is compiler-generated
    private val continuation : Continuation<*> = object : Continuation<Any?> {
        override fun run(value: Any?) = step(value)
    }

    // compiler-generated from the coroutine body
    // the parameter `continuationParameter` represents the result of the last `await` expression and is cast to an appropriate type in each step
    fun step(continuationParameter: Any?) {
        // Original source:
        // ```
        // println("start")
        // val s = await(a)
        // println(s)
        // val n = await(b)
        // println(n)
        // return@async "done"
        // ```
        when(this.state) {
        // The step before the first `await`
            0 -> {
                // `continuationParameter` is ignored for the first step, because the coroutine is parameterless
                println("start")
                this.state = 1
                controller.await(this.closure.a, continuation as Continuation<String>) // a deliberate unchecked cast in compiler-generated code
                return
            }

        // The step between the first `await` and the second `await`
            1 -> {
                val s = continuationParameter as String
                println(s)
                this.state = 2
                controller.await(this.closure.b, continuation as Continuation<Int>)
                return
            }

        // The step after the second `await`
            2 -> {
                val n = continuationParameter as Int
                println(n)
                this.state = -1
                controller.returnResult("done")
                return
            }

            -1 -> throw IllegalStateException("The state machine has already stopped")

            else -> {
                throw IllegalStateException("Unexpected state $state")
            }

        }
    }
}

// compiler-generated closure
// its name is not essential and is not visible to the user
data class GeneratorClosure(var n : Int)




// The source definition of `run`
fun run() {
    // captured in the closure, shared between all instances of a state machine
    var n = 0

    for (iteration in 1..2) {
        // `generate` is defined in the standard library and provides a controller for generator coroutines
        // it does not provide any arguments to the coroutine, all required data are to be provided via a the closure
    }
}

// A compiler-generated state machine
class GeneratorStateMachine(
        // the type of this property is derived from the `cofun` parameter type: (GeneratorController<T>) -> StateMachine<Unit>
        override val controller: GeneratorController<Int>
) : Coroutine<Unit, GeneratorController<Int>, Sequence<Int>> {

    // initialized by the compiler upon creation of the state machine
    lateinit var closure : GeneratorClosure

    private var state = 0

    override fun start(parameters : Unit) {
        step(null)
    }

    // The type argument `Any?` is erased and thus is not relevant, because this code is compiler-generated
    private val continuation : Continuation<*> = object : Continuation<Any?> {
        override fun run(value: Any?) = step(value)
    }

    // compiler-generated from the coroutine body
    // the parameter `continuationParameter` represents the result of the last `yield` expression
    // and is always `Unit` in this type of coroutines
    fun step(continuationParameter: Any?) {
        // Original code:
        // ```
        // println("start")
        // `yield`(n++)
        // println("between")
        // `yield`(n)
        // println("after")
        // return@generate Unit
        // ```
        when(this.state) {
            0 -> {
                println("start")
                this.state = 1
                controller.`yield`(closure.n++, continuation as Continuation<Unit>)
                return;
            }

            1 -> {
                println("between")
                this.state = 2
                controller.`yield`(closure.n, continuation as Continuation<Unit>)
                return;

            }

            2 -> {
                println("after")
                this.state = -1
                controller.returnResult(Unit)
                return
            }

            -1 -> throw IllegalStateException("The state machine has already stopped")

            else -> {
                throw IllegalStateException("Unexpected state $state")
            }

        }
    }
}

```

TODO: exception handling, serialization, yield break
CONSIDER: merge sequence and iterator into a single object, for a single call to `iterator`.
CONSIDER: save class declarations, and save class instances
A method used in a yielding invocation shall return the `Unit` type.

TODO: do not lift locals local to a single step
TODO: resolve rules for yieldable invocations (implicit receiver?), do not expose infrastructure methods inside coroutine

TODO: restrict acceess to a controller methods to nested lambdas and coroutines
TODO: type argument inference in nested generators
TODO: do we need a controller at all? Can we make `yield` and `await` top-level functions?
TODO: it is true that `await` would be inadvertently available everywhere?



An asynchronous coroutine is constructed by passing a coroutine (whose body may contain `await` yielding invocations) to the standard function `async`. The `async` function constructs a state machine, and starts execution of its first step synchronously, before returning control to its caller. At the first `await` yielding invocation there are two possibilities. 
* Either the awaited future is already completed, in which case the `await` invocation just returns its result and synchronously proceeds to the execution of the next step (if the end of the coroutine body is reached in this manner, then the `async` function returns a future that is already in the completed states, and is just a wrapper around the return value of the coroutine)
* Or, the awaited future is not yet completed, in which case the `async` function subscribes the continuation of the coroutine for the completion of the future, creates a future (to serve as the return value of the `async` function) whose completion represents end of the execution of the coroutine body, and return the future. In this case, the execution of the coroutine body will automatically resume on the completion of the awaited future, with the result of the future becoming the result of the `await` invocation. The execution can be further suspended and resumed multiple times on `await` expression, until it completes and signals the completion of the future that has been returned from the `async` method (which, in turn, can trigger resumption of coroutines currently awaiting for this future, and propagation of the future's result to them)

Note than, a state machine for asynchrous coroutines is created only once and immediately on the invocation of the `async` function, in contrast to generators, where multiple state machines may be created for multiple invocation of the `iterator` method, and their creation is delayed until the first such invocation.

TODO: variance modifiers

No parameters - Unit
return continuation - Nothing
finially back constaints
exceptions in futures
cancellation in futures
closable tasks?