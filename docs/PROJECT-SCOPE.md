# Open Muscle Connect: Project Scope

## What we are building

An Android companion application for the OpenMuscle FlexGrid wearable. The app should let someone use the wearable in the field without needing a laptop nearby. Same workflow as the existing PC application, on a phone.

## Target user

Three audiences, in priority order:

1. **Researchers** (the primary user today): Tory, Christopher (prosthetist in training), and other clinical or academic collaborators who currently carry a laptop with the PC app to do training sessions. The phone replaces the laptop.
2. **Amputees and patients in clinical settings**: users wearing the FlexGrid for prosthetic control training. The app may be operated by their clinician or by themselves.
3. **Future hobbyists and developers**: makers who want to use FlexGrid in their own projects without the PC tooling.

## Capabilities required (functional)

### Connectivity

- **Bluetooth Low Energy (BLE) connection to the wearable**. The wearable currently broadcasts sensor frames over Wi-Fi UDP; this is the primary new transport for the mobile use case. **Adding BLE on the wearable is firmware work** that the firmware team (or Tory) will do in parallel; the Android app should be designed to consume whatever format the firmware exposes.
- **Wi-Fi UDP connection to the wearable** as an alternate transport, matching the existing PC app. Useful when the user is on a known network and wants higher bandwidth.
- **Pairing with the OpenMuscle VR application**. Likely the VR app runs on a Quest headset and the Android app acts as the data and inference hub; the phone tells the headset what the model is predicting. Architecture TBD.

### Sensor visualization

- **Live 15 by 4 Velostat heatmap**, the same view the PC app shows.
- **Per-channel raw values** view for debugging.
- **Predicted hand pose** visualization, rendered as a simple 3D hand on the phone screen.

### Model training

- **Capture labeled sessions**: user wears the FlexGrid, makes hand movements, and the app records sensor frames paired with ground-truth labels.
- **Label source options**: at minimum, manual labels (user taps a button for each finger position) and integration with the LASK5 hand labeler over BLE or Wi-Fi. Optional: phone camera + on-device hand-pose detection (MediaPipe Hands or similar) as a label source.
- **Train an SGDRegressor or equivalent lightweight model on-device**. The PC app currently uses scikit-learn SGDRegressor and gets useful predictions from ~2000 samples; the Android equivalent should match this fidelity.
- **Adaptive training**: progressively update the model as the user continues to wear the band.

### Inference

- **Real-time inference** at the wearable's scan rate (currently 59 Hz on V3, similar on V4). Latency budget: under 50 ms from sensor frame received to predicted hand pose displayed.
- **Predicted hand pose output**: 4-channel piston positions for the OpenHand robot, or finger flexion values for the VR app, or both depending on the active mode.

### Session management

- **Local session storage** (SQLite or similar).
- **Export** to the same format the PC app reads, for cross-platform compatibility.
- **Cloud sync** is OPTIONAL for the first version; mention it but do not block on it.

### Battery and power

- **Background streaming** should be possible without killing the phone battery in an hour.
- **Wearable battery status** should be displayed when it is available (firmware needs to expose this).

## Capabilities NOT in scope (for v1)

These are valid future directions but should not slow down v1:

- iOS support
- Cloud-backed model training (training on a server, deploying to the phone)
- Multi-user accounts or social features
- E-commerce or in-app purchase
- Hardware configuration tools (those stay on the PC app)
- Firmware flashing from the phone
- AR overlays on the phone camera (the VR app handles that on the headset)

## Non-functional requirements

### Reliability

- Connection drops between the wearable and the phone are expected (BLE is flaky, Wi-Fi can roam). The app must reconnect automatically without user intervention.
- The app must not crash mid-session and lose unsaved training data. Persist incrementally.

### Performance

- Real-time heatmap rendering at the wearable's scan rate without dropped frames.
- Inference latency under 50 ms end-to-end.
- The training loop should not block the UI thread.

### Privacy

- All sensor data and trained models stay on-device by default. Cloud sync is opt-in.
- No telemetry to third parties.

### Accessibility

- Large, high-contrast UI elements. The primary user may be an amputee or a clinician working hands-busy.
- Voice commands are a stretch goal, not a requirement.

## Definition of done for v1

The app is "v1 done" when an able-bodied user can:

1. Pair with a FlexGrid V4 wearable over BLE.
2. See the live sensor heatmap.
3. Capture a 2-3 minute labeled training session using manual labels.
4. Train an SGDRegressor on-device from that session.
5. Run real-time inference on subsequent sensor frames, displaying predicted hand pose.
6. Optionally export the session to the PC app for comparison.

That is the floor. Pairing with VR, cloud sync, and other niceties come after v1 lands.
