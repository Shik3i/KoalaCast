# App-specific R8 rules. Libraries provide the consumer rules for Room, Hilt,
# Coil and Media3; what follows covers the gaps R8's full mode leaves.
#
# Full mode is the default from AGP 8 and is more aggressive than the classic
# one: it discards generic signatures and attributes that nothing demonstrably
# reads. Retrofit does read them — reflectively, at the moment it builds a
# service method — so a `suspend fun foo(): Response<Dto>` whose Continuation
# type has been erased asks for a converter for `java.lang.Object` and fails
# with "Unable to create converter for class java.lang.Object". That failure
# appears only in a minified build, only at runtime, and only on the calls
# actually made — which is how it can sit for months behind a caught exception
# while a listener wonders why their data never reaches the server.

# Retrofit reflects over the declared types of interface methods.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# The three types Retrofit resolves through generics on every suspend call.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# The API surface itself: its methods are never called directly, only proxied.
-keep,allowobfuscation interface net.koalastuff.koalacast.core.network.KoalaCastApi { *; }

# Serializable payloads. kotlinx ships consumer rules, but under full mode the
# generated `Companion.serializer()` of a class reached only through a Retrofit
# proxy is not always provably live.
-keepclassmembers @kotlinx.serialization.Serializable class net.koalastuff.koalacast.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class net.koalastuff.koalacast.core.network.dto.** { *; }
