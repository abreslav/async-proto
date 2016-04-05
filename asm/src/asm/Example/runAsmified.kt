package asm.example

import example.Example
import java.io.File

fun main(args: Array<String>) {
    val bytes = ExampleDump.dump()
    File("out/production/asm/example/Example.class").writeBytes(bytes)
    Example().main()
}