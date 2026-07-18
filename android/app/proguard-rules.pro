
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.pgpainless.** { *; }
-dontwarn org.pgpainless.**
-dontwarn org.slf4j.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
