# Recent Apps: Commit 3 TV test

This commit improves opt-in cached previews on PR #36. It does not add the finished app-switcher carousel or alter Kingdom, Tide or Shizuku task actions.

## Build on Windows

Run git fetch origin, git switch feature/recent-apps-switcher, git pull, then .\gradlew.bat assembleDebug. Install the debug APK using your normal ADB process.

Open the diagnostics with:
adb shell am start -n com.aktvhome.launcher/.recent.RecentAppsLabActivity

## Test cases

1. Enable Automatic previews in the lab if not already ON. Open SmartTube, navigate between several different screens for at least 10 seconds, and return Home. Reopen the lab, refresh tasks and choose Cached preview for SmartTube. Verify the image reflects recent navigation.
2. Repeat with SmartTube playback and press Home directly. The Home key now requests a last screenshot while the app is still visible, without waiting before showing Haven. This is best-effort, not guaranteed.
3. Check Last attempt and Last success timestamps and the outcome under the app entry. Wait a few seconds, change screens, and verify the times update. Only one JPEG per app should exist.
4. Open YouTube TV, including a live channel, and inspect its preview. Protected video pixels can remain black. If the app exposes MediaSession artwork or a title, Cached preview may use it instead. The label indicates the actual source.
5. Open Netflix and inspect Cached preview. If Netflix doesn't publish usable media metadata, its banner/icon remains the expected fallback. Do not assume protected video can be captured.
6. Check that a secure/blank capture does not reuse a misleading stale image. Check that an unrelated capture failure retains an existing good image.
7. Leave automatic previews OFF when finished if you do not want screenshots stored. Clear previews removes files, but does not change the ON/OFF setting.

## Limits and expectations

- All previews stay in Haven's private on-device cache. No network upload and no new service.
- Only launchable apps are captured, not Haven itself. Capture is event-driven: after app entry, relevant navigation, or the intercepted Home key.
- Normal navigation captures are limited to a six-second per-app cooldown, app re-entry to eight seconds, and a global 1.5-second cooldown. There is no periodic background capture.
- One JPEG (max width 480px) per app, maximum twelve apps in cache. A new successful image replaces the old image.
- A mostly black preview is rejected; a partly dark preview is labeled, and media artwork is preferred when publicly exposed by that app's media session.
- We do not try to bypass protected DRM video. Metadata availability is controlled by the streaming app, not Haven.
- Pressing Home is never delayed for the screenshot; Android may finish capturing after the app is gone, in which case the late capture is discarded.
- Android Gradle build and Sony TV behavior have not been verified from this environment. Keep the draft PR open until the local build and device tests pass.
