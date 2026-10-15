# Runtime reflection and optional providers

The native APK depends only on the Java/Android host API and verification kernel.
It contains no Kotlin reflection or third-party implementation. The shared runtime
does not explicitly depend on `kotlin-reflect`; compiler/test tooling entries in
Gradle locks are not packaged runtime dependencies.

Reflection retained for implementation purposes:

- Signed entry construction and Android 8/9 classloader adaptation in the native
  kernel, with the modern framework component factory on supported Android.
- The Android 8-10 resource adapter. Android 11+ exclusively uses public
  `ResourcesLoader`/`ResourcesProvider` APIs for distinct resource package IDs.
- Gson model metadata, library AAR consumer rules and JNI registration. The SDK
  shrinker retains only actual consumer-referenced class ABIs, and mandatory
  entry points, rather than entire AndroidX/Kotlin/HTTP packages.
- The camera implementation is resolved only through its installed component's
  owning classloader. CameraX does not enter the APK or mandatory SDK.

The artifact auditor reports, separately, the following guarded optional probes
inside the shared SDK. They are not exempted in business components:

- The pinned `okhttp-android:5.1.0` source artifact contains
  `androidMain/okhttp3/internal/platform/android/ConscryptSocketAdapter.kt` and
  `BouncyCastleSocketAdapter.kt`. Both call `Class.forName` first and catch absent
  provider classes; Conscrypt additionally catches `NoClassDefFoundError`.
  These providers are optional; the client uses Android's normal TLS stack.
- The pinned `androidx.window:window:1.5.1` source contains `ConsumerAdapter.kt`
  and `SafeWindowLayoutComponentProvider.kt`, which probe OEM window extension
  capabilities. `ConsumerAdapter` catches `ClassNotFoundException` and returns
  null. OEM extension function interfaces need not be shipped on every device.

All other unresolved class/member references fail delivery audit. SDK and
dependency class definitions cannot shadow each other. AppCompat must have one
owner in the shared runtime, including its view classes, because CameraX and
Markwon both reference it.
