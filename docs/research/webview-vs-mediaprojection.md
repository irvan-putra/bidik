# Capturing how a web page looks on a phone, from an app: two routes

Status: design note from documentation read on 2026-11-16. Nothing here was measured on a particular phone. Fit to a given site must be tested.

## Route 1: render in the app's own WebView
- An app can load a page in its own WebView and take a bitmap of that view. Android's WebView reference deprecates `capturePicture()` and points to drawing the view (`onDraw(Canvas)`) or `saveWebArchive(String)`. https://developer.android.com/reference/android/webkit/WebView
- Layout settings such as `setUseWideViewPort` and `setTextZoom` exist on `WebSettings`, so the viewport and text scale can be set to resemble a browser. How close the result is to what the phone's browser shows is something to test, since a WebView is not the same product as the browser and has no browser logins or sync. https://developer.android.com/reference/android/webkit/WebSettings
- This route stays inside the app's own content, so the Android screen-capture permission flow is not part of it.

## Route 2: capture the real browser's screen
- Android's MediaProjection API requires the app to ask for user consent before each projection session. A session is one call to `createVirtualDisplay()`, and a token is used once. Android 14 adds sharing of a single app window. https://developer.android.com/media/grow/media-projection
- So capturing a real browser tab, including pages that need a login, involves a consent step per session and is more fragile to automate.

## Choosing
Use the WebView route for pages the author owns and that need no login, and the MediaProjection route when the real browser's rendering or session is essential. Both need testing for the specific page.
