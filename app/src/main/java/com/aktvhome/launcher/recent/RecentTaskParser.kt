package com.aktvhome.launcher.recent

data class RecentTask(val taskId: Int, val pkg: String)

/** Parses Android 12's dumpsys recent-task blocks; falls back to am stack list. */
object RecentTaskParser {
    private val recent = Regex("""\* Recent #\d+:""")
    private val id = Regex("""Task\{[0-9a-fA-F]+ #(\d+)""")
    private val affinity = Regex("""A=\d+:([\w.]+)""")
    private val real = Regex("""realActivity=([\w.]+)/""")
    private val component = Regex("""cmp=([\w.]+)/""")
    private val stack = Regex("""taskId=(\d+): ([\w.]+)/""")

    fun parse(raw: String, ownPackage: String): List<RecentTask> {
        val blocks = recent.split(raw)
        val result = if (blocks.size > 1) {
            blocks.drop(1).mapNotNull { block ->
                val taskId = id.find(block)?.groupValues?.get(1)?.toIntOrNull()
                val pkg = affinity.find(block)?.groupValues?.get(1)
                    ?: real.find(block)?.groupValues?.get(1)
                    ?: component.find(block)?.groupValues?.get(1)
                if (taskId != null && pkg != null) RecentTask(taskId, pkg) else null
            }
        } else {
            stack.findAll(raw).mapNotNull { m ->
                m.groupValues[1].toIntOrNull()?.let { RecentTask(it, m.groupValues[2]) }
            }.toList()
        }
        return result.filter {
            it.taskId > 0 && it.pkg != ownPackage &&
                it.pkg != "com.android.systemui" &&
                it.pkg != "moe.shizuku.privileged.api" &&
                !it.pkg.contains("launcher", ignoreCase = true)
        }.distinctBy { it.pkg }.take(12)
    }
}
