# Hermetica Android SDK

Events, identity, push notifications, in-app messages and geofences for Android apps. Kotlin, minSdk 21, no required
dependencies: Firebase Messaging and Play Services Location are used only when your app includes them.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.hermeticacomms:hermetica:0.1.0")
}
```

## Quick start

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hermetica.initialize(this, siteKey = "pk_live_...", baseUrl = "https://api.example.com")
    }
}

Hermetica.identify("user-123", email = "ada@example.com", phone = "+15555550100")
Hermetica.setProfileAttributes(mapOf("first_name" to "Ada", "plan" to "pro"))
Hermetica.track("add_to_cart", mapOf("sku" to "A1"), value = 1999, currency = "USD") // value in cents
Hermetica.screen("Checkout")
Hermetica.reset() // on logout
```

Every call returns immediately and is safe from any thread. Events are stored on the device, sent in batches (every few
seconds, at 20 events, and when the app goes to the background) and retried with backoff, so nothing is lost offline
or when the app is killed. If the queue cannot be sent when the app goes to the background, a WorkManager job sends it
as soon as a network is available (no setup needed; it uses your WorkManager configuration if you have one). Each request carries an `Idempotency-Key`, and each event a dedupe key, so retries never
count twice. The SDK creates and stores an anonymous id for the device; `identify` links that history to the profile.

### Privacy

```kotlin
Hermetica.setTrackingOptOut(true)  // nothing is tracked or sent; queued events are dropped; remembered across launches
Hermetica.setConsent(analytics = true, marketing = true) // workspaces that require consent: what the person agreed to
```

Opting out of tracking does not unregister push: people keep the messages they chose. Consent and suppression are also
enforced on the server.

## Push (FCM)

1. Add Firebase to your app (`google-services.json`, the `com.google.gms.google-services` plugin) and
   `implementation("com.google.firebase:firebase-messaging:<version>")`.
2. Register the SDK's messaging service (or extend it and call `super`):

```xml
<service android:name="com.hermeticacomms.sdk.HermeticaMessagingService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
</service>
```

The SDK fetches the current token at start, registers new tokens, re-registers when the person changes (identify,
reset) or when notification permission changes, and shows Hermetica pushes that arrive while the app is open.
Request `POST_NOTIFICATIONS` on Android 13+ yourself; `Hermetica.pushPermissionStatus()` reports `AUTHORIZED` or `DENIED`.

Track opens and follow the deep link in the activity the notification opens (your launcher activity by default):

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Hermetica.handlePushOpened(intent)?.let { Hermetica.openDeepLink(this, it) }
}
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Hermetica.handlePushOpened(intent)?.let { Hermetica.openDeepLink(this, it) }
}
```

Call `Hermetica.unregisterPushToken()` before `reset()` if the next person on the device must not get the previous one's pushes.

## In-app messages

Messages are fetched when the app comes to the foreground, on `screen(...)` and on `track(...)`, and shown by a built-in
presenter (modal, banner, bottom sheet or full screen, with image, text and buttons; plain Android views, no Compose or
AppCompat needed). Impressions, clicks and dismissals are reported for you. Who sees what, and how often, is decided by the server.

To handle buttons or render messages yourself:

```kotlin
Hermetica.setInAppListener(object : InAppListener {
    override fun onMessage(message: InAppMessage): Boolean = false // return true to render it yourself
    override fun onAction(action: String, value: String, message: InAppMessage): Boolean = false // true to skip the default
})
```

When you render a message yourself, report it: `Hermetica.reportInAppEvent(message, "impression")`, then `"click"`
(with the button label) or `"dismiss"`. `Hermetica.fetchInAppMessages(InAppTrigger.Screen("Home")) { messages -> }`
fetches without showing.

## Geofences

Opt-in. Add `implementation("com.google.android.gms:play-services-location:<version>")`, get `ACCESS_FINE_LOCATION`
(and `ACCESS_BACKGROUND_LOCATION` for transitions while the app is closed) from the person yourself, then:

```kotlin
Hermetica.startGeofencing() // registers the workspace's geofences; does nothing without the permission
Hermetica.stopGeofencing()
```

Enter and exit are sent as `$geofence_enter` / `$geofence_exit` events, so journeys can start on them. With your own
geofencing library, use `Hermetica.fetchGeofences { fences -> }` and `Hermetica.reportGeofence("enter", fenceId)`.

## Building

The Gradle wrapper jar is not committed. Run `gradle wrapper` once (Gradle 8.11+), then `./gradlew test lint assembleRelease`.

## License

MIT
