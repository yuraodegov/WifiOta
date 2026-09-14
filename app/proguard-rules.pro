# R8 rules for the release build.
#
# OkHttp and Okio ship their own rules inside their artifacts, so nothing is
# needed for them here. What follows covers the two places where R8 cannot see
# that something is used.

# Reflection-free project, so classes can be renamed freely. Keep line numbers
# and map them, otherwise a stack trace from a technician's phone is unreadable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Referenced from XML by name, never from code.
-keep class com.strauss.wifiota.MainActivity { *; }

# Coroutines' internal service loading confuses the shrinker on some versions.
-dontwarn kotlinx.coroutines.**

# Conscrypt and friends are optional OkHttp back ends that are not on the
# device. Without this R8 warns about references it cannot resolve.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
