# Keep the Java bridge and every JNI entry point; native name lookup is not R8-visible.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# Retain runtime annotations, generic signatures, and nested-class metadata used by
# Kotlin/Java reflection and platform/framework integrations.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Honor explicit AndroidX Keep annotations on classes and their members.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Preserve Parcelable creators and Serializable model fields if models are consumed
# through framework reflection or persisted representations.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}
