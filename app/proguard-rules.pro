# Add project specific ProGuard rules here.

# ML Kit creates its component registrars by reflection (they're listed in the merged
# manifest). The library's own rule keeps the classes, but under R8 full mode that no
# longer keeps their no-arg constructors, so ML Kit can't create them and the release
# build crashes on launch when the text recognizer is first used.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
