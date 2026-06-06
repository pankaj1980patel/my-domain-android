# Keep the JNI bridge: method names must survive shrinking so the native
# library can resolve them.
-keep class com.mydomain.android.RustNet { *; }
