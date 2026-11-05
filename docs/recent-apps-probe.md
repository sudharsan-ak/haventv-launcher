# Haven Recent Apps: Commit 4 media metadata investigation

This is a diagnostic continuation of PR #36. It does NOT implement the final carousel, does not change Kingdom/Tide, and does not bypass protected video.

## What changed

- While an application is foregrounded, Haven observes its existing MediaSession through the same Notification Listener permission used by Now Playing/NBA alerts. Media-session callbacks catch metadata changes even when the app stops producing Accessibility UI events.
- Haven stores the last permitted title and at most one small artwork JPEG per app. This makes metadata available after the app goes to the background and its MediaSession disappears.
- Artwork can come from MediaMetadata bitmaps or its artwork URI fields, decoded/downscaled with bounded reads. HTTP(S) artwork URIs may cause a small image download; no video or screen is uploaded.
- Secure apps with no readable Accessibility root can still supply metadata through a foreground window event.
- Cached preview now chooses actual screenshot when usable, then last foreground media metadata, live session metadata, a recent Watch Next item, and finally the app's own banner/icon. Partially black screenshots prefer useful alternative artwork when available.
- Watch Next is read using Haven's existing READ_TV_LISTINGS permission. A recent Watch Next item is ALWAYS labeled as a recommendation/history item, not an exact playing frame.
- More diagnostics report notification access, no sessions, missing metadata, unreadable artwork URI, cached media title/source/time, and missing Watch Next rows.
- Clear previews removes both screenshot and media caches. All screenshot behavior and Shizuku task actions are retained.

## Pull and test on Sony TV

In PowerShell in your existing repository:

    git fetch origin
    git switch feature/recent-apps-switcher
    git pull
    .\gradlew.bat assembleDebug
    adb install -r app\build\outputs\apk\debug\app-debug.apk
    adb shell am start -n com.aktvhome.launcher/.recent.RecentAppsLabActivity

If necessary, re-enable Haven's existing Home button Accessibility service after updating the APK. Automatic previews must be ON in the diagnostic screen.

Test each service separately:

1. SmartTube: navigate and return Home. It should still display a normal screenshot. The new media diagnostics may additionally show metadata.
2. YouTube TV: start live playback, remain there 10-20 seconds, return Home, reopen diagnostic screen, press Connect / Refresh tasks, then Cached preview. Check the media status line and the label. Video pixels are still protected.
3. Netflix: start playing a title, remain there 10-20 seconds, return Home, inspect Cached preview and the media status. It may still only show the app banner if Netflix exposes no usable metadata or provider rows.
4. Prime Video: repeat the Netflix test.
5. Inspect the text for each app: last screenshot attempt, last successful screenshot, last media observation, media source, any Watch Next query result, and final preview label.
6. Switch automatic previews OFF and ON, verify it remains responsive. Clear previews should remove the media and screenshot cache entries.

If metadata is missing, confirm Notification access for Haven is enabled (its existing Now Playing functionality depends on it). The optional Watch Next query also requires READ_TV_LISTINGS; a missing grant is reported, not silently treated as no content.

## Behavior limits

- Media artwork and program titles come from Android APIs when apps publish them. Netflix and Prime Video are not guaranteed to expose them.
- Last foreground media is retained for up to 30 minutes and labeled by source; it is not represented as an exact screenshot.
- Watch Next is limited to the 500 visible provider rows queried, and only uses same-package items engaged within 48 hours. It may reflect a previously watched title rather than the current session.
- The separate screenshot cache retains one JPEG per app, max 12 apps. The media artwork cache similarly retains at most one artwork JPEG per app, max 12 files.
- No capture of DRM-protected video is attempted.
- Source changes still need Android Gradle compilation and Sony TV verification by the user; no on-device test has run in this environment.
- Leave PR #36 open and unmerged until testing and the final switcher UI are completed.


## Commit 5: prefer real screenshots to generic media artwork

Commit 4 introduced a UI selection regression: its "large dark area" heuristic
overrode a valid YouTube TV browse/home screenshot with Friends artwork from
the media session. Most TV app interfaces are intentionally dark. A mostly
dark screenshot is not necessarily a failed or protected capture.

The diagnostic viewer now always displays a **usable saved screenshot first**,
regardless of the "large dark region" flag. That flag remains visible as a
diagnostic, not a fallback selector. MediaSession artwork and Watch Next are
only queried for the preview if no screenshot was saved; app artwork is still
the last fallback. Four selection unit tests cover the expected priority.

### Sony TV regression test

1. Leave Automatic previews ON. Browse YouTube TV's Home/Library/Live pages,
   spend several seconds there, then return Home to Haven.
2. Open the lab and choose Cached preview. The *actual YouTube TV interface*
   should appear, even if tagged "dark UI"; it must not be replaced by Friends
   media artwork.
3. Try a YouTube TV channel. Protected video may still be black or cause the
   screenshot to be discarded; this is expected, not a capture bypass.
4. Prime Video browse screens and SmartTube should continue showing captured
   screenshots. Netflix or Prime's protected video may still show fallbacks.
5. If the screen is not updating, inspect Last attempt and Last success; do not
   confuse a missing JPEG with the separate media metadata cache.

No screenshot cadence, permissions, capture-security logic, or launcher layouts
changed in this fix. Build and Sony TV behavior still need local verification.
