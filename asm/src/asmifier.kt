
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.ASMifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

fun main(args: Array<String>) {
    val stringWriter = StringWriter()
    ClassReader("example/example").accept(
            TraceClassVisitor(null, ASMifier(), PrintWriter(stringWriter)), 0
    )
    val file = File("asm/src/asm/example/ExampleDump.java")
    file.parentFile.mkdirs()
    file.writeText(stringWriter.toString())
    println(stringWriter.buffer)
}