# SkyBot Lavaplayer Plugin

A custom Lavaplayer plugin providing additional audio sources
- **Speech** — text-to-speech using Google Translate.
- **PornHub** — play or search tracks from PornHub.


## Installation

Add the following dependency to your Gradle project:

```kotlin
dependencies {
    implementation("com.github.ferrymehdi:skybot-lavaplayer-plugin:{version}")
}
```

Replace `{version}` with the latest release tag.

---

## How to Use

### 1. Registering the Sources

In your Lavaplayer setup:

```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

playerManager.registerSourceManager(new SpeechAudioSourceManager("en"));
playerManager.registerSourceManager(new PornHubAudioSourceManager());
```

This will allow Lavaplayer to recognize and play the following identifiers:

- `speak:Hello world`
  → Generates speech audio using Google Translate TTS.

- A PornHub video link or search query:
  ```text
  https://www.pornhub.com/view_video.php?viewkey=abc123
  ```
  or
  ```text
  phsearch:keyword
  ```

---

### 2. Using It in Code (Examples)

**Example — Speech Source**
```java
playerManager.loadItem("speak:Hello everyone!", result -> {
    // handle track loading
});
```

**Example — PornHub Source**
```java
playerManager.loadItem("phsearch:funny", result -> {
    // handle playlist or single result
});
```

---

## Lavalink Support Notice

This plugin is **not compatible with Lavalink**.

---

## Sources Overview

| Source | Identifier Format | Description |
|--------|--------------------|--------------|
| SpeechAudioSourceManager | `speak:<text>` | Converts text into speech using Google Translate |
| PornHubAudioSourceManager | `phsearch:<query>` or full video URL | Fetches and plays videos or search results from PornHub |
