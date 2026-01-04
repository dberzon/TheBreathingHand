# The Breathing Hand ✋🎶

> **Golden Master 3c (Research Edition)**
> *An Android MIDI instrument tuned to human biomechanics.*

"The Breathing Hand" is a gestural MIDI controller that transforms multi-touch physics into musical harmony. Unlike standard touch controllers, it uses a **biomechanical input engine** derived from HCI research to filter human tremor and "breathe" with the player's hand.

![Status](https://img.shields.io/badge/Status-Golden%20Master%203c-success)
![Platform](https://img.shields.io/badge/Platform-Android%2014-green)
![Hardware](https://img.shields.io/badge/Hardware-USB%20MIDI-blue)

## 🌟 Key Features

### 1. "True Hand" Physics
Instead of tracking individual fingers, the engine calculates the **Centroid** and **Average Spread (Radius)** of the entire hand (up to 5 fingers). This allows the player to "breathe" the chord quality (Minor $\leftrightarrow$ Major) by naturally expanding their hand, rather than performing rigid gestures.

### 2. Biomechanically Tuned Engine
The input system was calibrated using specific HCI experiments ("The Statue", "The Step", "The Trill") to derive non-arbitrary constants:
* **Precision:** 2.0° angular hysteresis (derived from 0.8° human drift).
* **Stability:** 100px radius buffer (compensates for 73px natural hand pulse).
* **Temporal Logic:** 250ms "Retention Gate" to prevent dropped notes during accidental finger lifts.

### 3. Musical Intelligence
* **Circle of Fifths:** Sectors are mapped harmonically (C $\to$ G $\to$ D), not chromatically, for musical coherence.
* **Monotonic Voice Leading:** The audio engine automatically inverts chords to ensure pitch continuity, preventing "muddy" low notes from jumping over high notes.
* **Zero-Allocation Loop:** The core audio thread runs without triggering Garbage Collection, ensuring sub-16ms latency.

---

## 🛠️ Installation & Setup

### Requirements
* **Software:** Android 14 (API 34) or higher.
* **Hardware:**
    * Android Device with USB-OTG support.
    * USB A-to-C Adapter (OTG Cable).
    * Class-Compliant USB MIDI Interface (Synth, Keyboard, or Sound Module).

### Quick Start
1.  **Clone the Repo:**
    ```bash
    git clone [https://github.com/YourUsername/TheBreathingHand.git](https://github.com/YourUsername/TheBreathingHand.git)
    ```
2.  **Open in Android Studio:** Ensure you are using Electric Eel or newer.
3.  **Connect Hardware:**
    * Plug your MIDI Synth into the phone via OTG **before** launching the app.
4.  **Run:**
    * Launch the app.
    * Look for the Toast message: *"Connected: [Your Device Name]"*.
    * Play!

---

## 🔬 Architecture Overview

The app follows a strict **Unidirectional Data Flow** architecture to maintain stability during performance.

| Component | Responsibility |
| :--- | :--- |
| **`MainActivity`** | The "Controller." Manages the high-precision timer (`nanoTime`) and USB hardware connection. |
| **`TouchMath`** | The "Physics Engine." Calculates the hand centroid and spread using raw MotionEvents. |
| **`OneEuroFilter`** | The "Signal Conditioner." Removes biomechanical jitter using an adaptive 1st-order low-pass filter. |
| **`HarmonicEngine`** | The "Brain." A State Machine that applies Spatial Hysteresis and Temporal Logic (Time Gates) to decide the musical intent. |
| **`VoiceLeader`** | The "Musician." Converts abstract harmonic states (Root/Quality/Density) into concrete MIDI messages. |
| **`InputTuning`** | The "Lab." Contains the scientifically derived constants for the engine. |

---

## 📊 HCI Research (Chapter 3C)

This build ("Golden Master 3c") implements findings from three specific biomechanical experiments:

| Experiment | Purpose | Resulting Constant |
| :--- | :--- | :--- |
| **A. "The Statue"** | Measured natural hand tremor while holding still. | `FILTER_MIN_CUTOFF = 1.0f` |
| **B. "The Step"** | Measured maximum hand velocity during state changes. | `FILTER_BETA = 0.02f` |
| **C. "The Trill"** | Measured angular precision during rapid oscillation. | `ANGLE_HYSTERESIS_DEG = 2.0f` |

---

## 📄 License

This project is open source.
* **Code:** MIT License
* **Concept:** "The Breathing Hand" (2026)

---

*Built with Kotlin and Physics.* ✋