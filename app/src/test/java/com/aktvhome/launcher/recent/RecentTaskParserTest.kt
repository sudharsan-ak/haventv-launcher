package com.aktvhome.launcher.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTaskParserTest {
    @Test fun parsesAndroidRecentTaskBlocksInOrder() {
        val raw = """
            ACTIVITY MANAGER RECENT TASKS
            * Recent #0: Task{a3b4c5 #412 type=standard A=0:com.google.android.youtube.tv}
              realActivity=com.google.android.youtube.tv/.MainActivity
            * Recent #1: Task{cd34fe #409 type=standard A=0:com.netflix.ninja}
              realActivity=com.netflix.ninja/.MainActivity
            * Recent #2: Task{10bafe #401 type=home A=0:com.aktvhome.launcher}
        """.trimIndent()
        assertEquals(
            listOf(RecentTask(412, "com.google.android.youtube.tv"), RecentTask(409, "com.netflix.ninja")),
            RecentTaskParser.parse(raw, "com.aktvhome.launcher"),
        )
    }

    @Test fun fallsBackToAmStackList() {
        val raw = "taskId=81: com.netflix.ninja/.Main taskId=62: com.google.android.youtube.tv/.Main"
        assertEquals(2, RecentTaskParser.parse(raw, "com.aktvhome.launcher").size)
    }

    @Test fun deduplicatesPackagesAndIgnoresOwnTask() {
        val raw = """
            * Recent #0: Task{abc #90 type=standard A=0:com.netflix.ninja}
            * Recent #1: Task{def #85 type=standard A=0:com.netflix.ninja}
            * Recent #2: Task{abc #80 type=standard A=0:com.aktvhome.launcher}
        """.trimIndent()
        assertEquals(listOf(RecentTask(90, "com.netflix.ninja")),
            RecentTaskParser.parse(raw, "com.aktvhome.launcher"))
    }
}
