Bilal Utility v1.9.0 - Auto Elytra update

Base: v1.8.0 fixed source
Minecraft: 1.21.11
Yarn: 1.21.11+build.4
Java: 21

REPLACEMENT FILES
Copy these files into your project, replacing the files at the same paths:

1. src/main/java/com/bilalutility/flight/FlightRunner.java
2. src/main/java/com/bilalutility/flight/FlightConfig.java
3. src/main/java/com/bilalutility/flight/FlightScreen.java
4. src/main/java/com/bilalutility/flight/FlightClient.java
5. gradle.properties

No other module was intentionally changed. FlightHud.java was not changed; it continues showing the HUD while the runner is active, including while the queue is paused after landing failures.

WHAT CHANGED
- Added a Debug mode setting (off by default). When enabled, Auto Elytra writes destination, detection, position, rocket and landing diagnostics to chat.
- Ship landing now estimates the highest nearby solid surface as the roof height and keeps that height as the landing reference.
- A ship landing is not treated as success unless the player is on the roof level and within the landing radius.
- A landing that touches down below/away from the roof triggers another approach, up to 3 attempts.
- After 3 failed attempts, the queue pauses. The destination is not marked complete. Press Start flight to retry it; the sneak key still stops the flight.
- Looted points remain in the queue list and get an individual Reactivate button. Reactivate clears only that point's result.
- Updated mod_version to 1.9.0.

IMPORTANT TEST NOTE
This environment did not have Gradle or a Gradle wrapper installed, so a full Gradle compile was not run. The changed Java files passed basic structural checks, but please run `gradle build --no-daemon` in your project before using it in-game. If Gradle reports an error, send the complete error output and I will fix that first.
