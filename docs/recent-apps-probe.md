# Recent Apps - Commit 1 technical proof of concept

This is a diagnostic build for Sony/Android TV 12 (API 31). It is **not** the final
Haven App Switcher. It does not alter Home, Kingdom, Tide, or the remote shortcuts.

## Setup

1. Install and start Shizuku on the TV. RecentAppsTV can remain installed.
2. Build Haven on this branch, install the debug APK normally.
3. Open the diagnostic activity from Windows PowerShell while ADB is connected:

   adb shell am start -n com.aktvhome.launcher/.recent.RecentAppsLabActivity

4. If it says "permission required", choose **Grant Shizuku** and allow Haven.
5. Choose **Connect / Refresh tasks**. Shizuku's UserService will attempt to read
   the real Android recent-task list from shell, with an am stack list fallback.

## Test sequence

- Open YouTube TV and SmartTube, then open the lab. Check whether their task
  IDs and packages appear in recent order.
- Choose **Snapshot** for each task and note whether a real preview is returned.
  Secure content or unsupported snapshots must report "unavailable", not fabricate
  an image. These saved system images are queried only when requested.
- Choose **Resume task**. It tries the existing task ID, with **no automatic app
  relaunch fallback in this diagnostic** so the result is meaningful.
- To test Android 12 Accessibility capture, choose **Capture & open** on a
  task. This explicitly arms a one-shot capture and opens the selected app.
  Return to the lab using the ADB command above to view the result.
- To test dismissal, choose **Remove** twice on a task that is safe to close.
  This only calls Android's removeTask; it does not force-stop the package.

## Safety and constraints

- Shizuku is optional. The lab shows a status message rather than crashing
  when missing or unauthorized. The normal Haven launcher remains unaffected.
- Nothing is captured continuously. One-shot Accessibility samples stay as a
  small JPEG in Haven's **private app cache**, overwritten by the next capture.
  System task snapshots live only in memory. No upload or network transfer.
- DRM/FLAG_SECURE content is not bypassed. Blank or inaccessible previews are
  normal and will later fall back to app banners/icons.
- A new accessibility capability is declared for one-shot screenshots. If
  capture fails with "no accessibility access", try disabling then re-enabling
  **Haven's Home button** Accessibility service after updating the APK.
- A successful task-list test does not imply successful snapshot, restore, or
  removal: test each independently and report exact errors from the lab.
- Only the **debug** build is intended for this first PR commit.
- No changes to main; the PR remains open until tested and final UI is approved.
