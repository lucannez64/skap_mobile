# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Gson serialization rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep all data classes used in serialization
-keep class eu.klyt.skap.lib.OfflineStorageManager$OfflinePasswordData { *; }
-keep class eu.klyt.skap.lib.Password { *; }
-keep class eu.klyt.skap.lib.ClientEx { *; }
-keep class eu.klyt.skap.lib.Client { *; }
-keep class eu.klyt.skap.lib.Uuid { *; }
-keep class eu.klyt.skap.lib.CK { *; }
-keep class eu.klyt.skap.lib.SharedPass { *; }
-keep class eu.klyt.skap.lib.EP { *; }
-keep class eu.klyt.skap.lib.AllPasswords { *; }
-keep class eu.klyt.skap.lib.SharedByUser { *; }
-keep class eu.klyt.skap.lib.SharedByUserEmail { *; }
-keep class eu.klyt.skap.lib.ReceivedCK { *; }

# Keep ShareStatus enum
-keep enum eu.klyt.skap.lib.ShareStatus { *; }

# Keep Kotlin data class methods
-keepclassmembers class eu.klyt.skap.lib.** {
    public <init>(...);
    public *** component*();
    public *** copy(...);
    public java.lang.String toString();
    public int hashCode();
    public boolean equals(java.lang.Object);
}

# Keep generic type information for collections
-keepattributes Signature
-keep class kotlin.Pair { *; }
-keep class kotlin.Triple { *; }
-keep class eu.klyt.skap.lib.Quadruple { *; }

# Keep fields of serialized classes
-keepclassmembers class eu.klyt.skap.lib.** {
    !transient <fields>;
}

# Keep classes that might be referenced by Gson
-keep class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile