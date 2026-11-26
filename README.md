# Gemini Vision for Meta Smart Glasses (Audio + Camera, No Display)

An Android companion app built with **Kotlin**, **Jetpack Compose**, and the **Meta Wearables Device Access Toolkit (DAT) SDK (`mwdat-core` & `mwdat-camera`)** that connects **Ray-Ban Meta / Oakley Meta smart glasses** (non-display models) to Google's **Gemini API** and **Gemini Multimodal Live API**.

## Features

- **Meta Glasses POV Camera (`mwdat-core` + `mwdat-camera`)**
  - Registers with the Meta AI app, requests `Permission.CAMERA` for the glasses, streams live POV video frames, and captures crisp still photos (`capturePhoto()`).
  - Shows glasses connection state, battery percentage, and live POV preview on the phone companion screen.
  - Includes an optional **Phone Camera Fallback** in Settings so you can test Gemini queries immediately even without glasses paired.
- **Hands-Free Audio-First Design (No Display Required)**
  - **Camera QA Mode (`POST /v1/interactions`)**: Ask questions via voice or text (or quick one-tap chips) using what your glasses see, powered by `gemini-4-hydrogen` with stateful `previous_interaction_id` multi-turn memory. Gemini's answers are **automatically spoken aloud** into your glasses speakers via Android TTS.
  - **Gemini Live Mode (`BidiGenerateContent` WebSocket)**: Real-time two-way voice conversation + continuous 1 FPS POV camera streaming from your glasses using `gemini-3.8-live` (`realtimeInput.audio`, `realtimeInput.video`, `realtimeInput.text`, and `audioStreamEnd` on mute). Automatically routes 16 kHz mic input and 24 kHz Gemini native audio output through your paired Meta glasses Bluetooth audio (with hardware AEC and instant barge-in interruption).

## Setup

1. **Enable Developer Mode in the Meta AI app** on your Android phone (required when using default `mwdat_application_id = "0"` and `mwdat_client_token = "0"`). For production distribution, set `MWDAT_APPLICATION_ID` and `MWDAT_CLIENT_TOKEN` in `local.properties` from the Meta Wearables Developer Center.
2. **Add your Gemini API key** from [Google AI Studio](https://aistudio.google.com/):
   - Either paste it directly inside the app's **Settings** sheet, **or**
   - Add `GEMINI_API_KEY=your_key_here` to `local.properties` before building.
3. **Build & Install**:
   ```powershell
   .\gradlew.bat assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
4. **Run Unit Tests**:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
