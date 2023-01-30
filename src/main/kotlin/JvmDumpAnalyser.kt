import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("DumpAnalyser")
    val fileName by parser.option(ArgType.String, shortName = "f", description = "Input file name").required()
    val filterBySizeLe by parser.option(
        ArgType.Int, shortName = "l",
        description = "Return only stack with number of threads less or equal then value passed"
    )
    val filterBySizeGe by parser.option(
        ArgType.Int, shortName = "g",
        description = "Return only stack with number of threads greater or equal then value passed"
    )
    val filterByKeyWordInStack by parser.option(
        ArgType.String, shortName = "t",
        description = "Return only stack with substrings passed (separated by commas)"
    )
    parser.parse(args)
    val toTypedArray: Array<String> = filterByKeyWordInStack
        ?.split(",")
        ?.map { it.trimIndent() }
        ?.toTypedArray()
        ?: emptyArray()

    val lines = File(fileName).readLines()
    val dump = readDumpFile(lines)
    val grouping = getGroupingByFirstLineInStack(dump)
    grouping.forEach skip@{ (key, value) ->
        if (filterBySizeLe != null && value.size > filterBySizeLe!!) {
            return@skip
        }
        if (filterBySizeGe != null && value.size < filterBySizeGe!!) {
            return@skip
        }
        if (toTypedArray.isNotEmpty()) {
            toTypedArray.firstOrNull { key.contains(it) } ?: return@skip
        }
        println("================= Total threads : ${value.size} =================")
        println(key)
        println()
        value.forEach {
            println("Thread ${it.id} - ${it.name} : ${it.state}");
        }
        println()
    }
}

private fun getGroupingByFirstLineInStack(dump: DumpStructure): MutableMap<String, MutableSet<ThreadDescription>> {
    val grouping: MutableMap<String, MutableSet<ThreadDescription>> = mutableMapOf()
    val waitingInStackRegex = Regex(
        """
        waiting on <([0-9a-zA-Z]+)>
        """.trimIndent()
    )
    val lockedInStackRegex = Regex(
        """
        locked <([0-9a-zA-Z]+)>
        """.trimIndent()
    )
    val parkingToWaitForStackRegex = Regex(
        """
        wait for <([0-9a-zA-Z]+)>
        """.trimIndent()
    )
    dump.tds.forEach {
        val stackTrace = it.stackTrace
        if (stackTrace.isNotEmpty()) {
            val stackTraceSinIds = stackTrace.replace(waitingInStackRegex) {
                "waiting on <X>"
            }.replace(lockedInStackRegex) {
                "locked <X>"
            }.replace(parkingToWaitForStackRegex) {
                "wait for <X>"
            }
            grouping.getOrPut(stackTraceSinIds.trimIndent(), ::mutableSetOf).add(it)
        }
    }
    return grouping
}

fun readDumpFile(rawLines: List<String>): DumpStructure {

    val threadHeadParser = ThreadHeadParser()

    var pos = 0
    val header = DumpStructureHeader(rawLines[pos++], rawLines[pos++])
    val threads = mutableListOf<ThreadDescription>()
    while (++pos < rawLines.size) {
        val line1 = rawLines[pos++]
        val (threadName, threadId) = threadHeadParser.getThreadIdAndName(line1)
        val line2 = rawLines[pos++]
        val threadState = ThreadState.valueOf(threadHeadParser.getThreadState(line2))
        val sb = StringBuilder()
        while (rawLines[pos].trimIndent().isNotEmpty()) {
            sb.append(rawLines[pos++].trimIndent()).appendln();
        }
        while (pos < rawLines.size - 1 && rawLines[++pos].trimIndent().isNotEmpty()) {
            //skip 'Locked ownable synchronizers'
            //todo add information about locks
        }
        threads.add(ThreadDescription(threadName, threadId, threadState, sb.toString()))
    }
    return DumpStructure(header, threads)
}

class ThreadHeadParser {
    companion object {
        lateinit var threadDumpHeadRegex: Regex
        lateinit var threadDumpStateRegex: Regex
    }

    init {
        threadDumpHeadRegex = Regex(
            """
        "([a-zA-Z0-9_\-.() \[\]]+)" - Thread (t@[a-zA-Z0-9_-]+)
        """.trimIndent()
        )
        threadDumpStateRegex = Regex(
            """
                java\.lang\.Thread\.State: ([_A-Z]+)
            """.trimIndent()
        )
    }

    fun getThreadIdAndName(str: String): Pair<String, String> {
        val matchResult = threadDumpHeadRegex.find(str)
        if (matchResult?.groups == null) {
            throw RuntimeException("Unknown format: {$str}");
        }
        if (matchResult.groups.size != 3) {
            throw RuntimeException("Illegal string format: {$str}");
        }
        val first = matchResult.groups[1]?.value ?: throw RuntimeException("Thread name can not be null {$str}")
        val second = matchResult.groups[2]?.value ?: throw RuntimeException("Thread id can not be null {$str}")
        return Pair(first, second)
    }

    fun getThreadState(str: String): String {
        val matchResult = threadDumpStateRegex.find(str)
        if (matchResult?.groups == null) {
            throw RuntimeException("Unknown format: {$str}");
        }
        if (matchResult.groups.size != 2) {
            throw RuntimeException("Illegal string format: {$str}");
        }
        return matchResult.groups[1]?.value ?: throw RuntimeException("Thread name can not be null {$str}")
    }

}

class DumpStructure(
    val header: DumpStructureHeader,
    val tds: List<ThreadDescription>
) {

    override fun toString(): String {
        return "DumpStructure(header=$header, tds=$tds)"
    }
}

class DumpStructureHeader(private val timestamp: String, private val description: String) {

    override fun toString(): String {
        return "DumpStructureHeader(timestamp='$timestamp', description='$description')"
    }
}

class ThreadDescription(
    val name: String, val id: String,
    val state: ThreadState, val stackTrace: String
) {

}

enum class ThreadState {
    WAITING,
    TIMED_WAITING,
    RUNNABLE
}