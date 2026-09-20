
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.pgpainless.**
-dontwarn org.slf4j.**
