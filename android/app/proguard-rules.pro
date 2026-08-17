# kotlinx.serialization generates serializers as companion members; keep them so release
# builds can still parse API responses.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.dividendstream.app.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.dividendstream.app.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit interfaces are reflective.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
