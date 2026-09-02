# Debug/prototype APK is not minified. Keep MapLibre JNI entry points if release minify is enabled later.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
