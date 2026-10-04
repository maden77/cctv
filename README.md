# CCTV Mandiri

A simple Android CCTV app built with Kotlin and CameraX. The app exposes a lightweight MJPEG streaming server from the camera device and allows a viewer client to connect over HTTP to watch the live camera feed.

## Features
- Camera service with foreground notification
- MJPEG live stream at `http://<device-ip>:8080/video`
- Motion detection using frame difference
- Automatic recording when motion is detected
- Local server control screen and remote viewer screen

## Project structure
- `app/src/main/java/com/example/cctv/` — Android app code
- `app/src/main/res/layout/` — UI layout files
- `app/src/main/res/values/` — app strings and theme
- `app/src/main/AndroidManifest.xml` — app permissions and app components

## Build
```bash
./gradlew assembleDebug
```

## Run
1. Open the app on the device.
2. Enter the server screen.
3. Start the camera service.
4. Use the IP shown on screen in the viewer app.
5. Connect to `http://<ip>:8080` and watch the MJPEG stream.

## Notes
- The app uses a basic username/password for the MJPEG stream:
  - username: `admin`
  - password: `cctv123`
- The app targets Android 34 and uses CameraX for camera access.
