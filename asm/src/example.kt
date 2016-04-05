package example

class Example {
    fun main() {
        try {
            p(1)
            p(2)
            p(3)
            p(4)
            p(5)
        } catch (e: IllegalStateException) {
            p(6)
            p(7)
            p(8)
        } catch (e: IllegalArgumentException) {
            p(9)
            p(10)
            p(11)
        }
    }

    fun p(i: Int) = println(i)
}