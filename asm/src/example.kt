package example

class Example {
    fun main() {
        p(1)
        printObject("HERE")
        try {
            p(3)
            p(4)
            p(5)
            throw IllegalStateException()
        } catch (e: IllegalStateException) {
            p(6)
            var e: IllegalStateException? = IllegalStateException()
            e = null
            printObject(e)
            p(8)
        } catch (e: IllegalArgumentException) {
            p(9)
            p(10)
            p(11)
        }
    }

    fun p(i: Int) = println(i)
    fun printObject(o: Any?) = println(o)
}