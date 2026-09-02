# Debug assemble does not minify. Keep MapLibre native symbols if release minify is enabled later.
-keep class org.maplibre.** { *; }
-keepclassmembers class org.maplibre.** { *; }
