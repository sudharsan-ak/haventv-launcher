# Shizuku loads this class by name in its shell-identity UserService process.
# Preserve the default constructor and Binder protocol even in minified releases.
-keep class com.aktvhome.launcher.recent.RecentTaskUserService { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
