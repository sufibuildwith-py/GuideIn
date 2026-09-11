<div align="center">

# ✦ GuideIn

### AI-Powered Career Guidance Assistant

**A Java-based AI career mentor built from the ground up with Google Gemini, REST APIs, and a custom desktop interface.**

<br>

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0A0B0D,50:151419,100:D8B36A&height=180&section=header&text=GUIDEIN&fontSize=48&fontColor=EEE9DF&animation=fadeIn&fontAlignY=38&desc=AI%20CAREER%20MENTOR&descSize=15&descAlignY=60&descColor=D8B36A" width="100%"/>

<br>

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Gemini](https://img.shields.io/badge/Google-Gemini-8E75B2?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev/)
[![Jackson](https://img.shields.io/badge/Jackson-JSON-2C2C2C?style=for-the-badge)](https://github.com/FasterXML/jackson)
[![Status](https://img.shields.io/badge/Status-Active%20Development-D8B36A?style=for-the-badge)](#-project-status)

<br>

> **GuideIn is evolving from a console-based Gemini experiment into a polished desktop AI career mentor.**

<br>

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=500&size=16&duration=3200&pause=1000&color=D8B36A&center=true&vCenter=true&width=700&lines=Ask+about+careers.;Explore+skills.;Prepare+for+interviews.;Build+better+projects.;Let+GuideIn+help." alt="GuideIn animated typing"/>

</div>

---

## ✦ What is GuideIn?

**GuideIn** is an AI-powered career guidance application written in **Java 21**.

The project started as a simple console application designed to understand what actually happens when a Java application communicates with a generative AI model through a REST API.

Instead of treating an AI API as a black box, GuideIn was built to explore the complete pipeline:

```text
User Input
    ↓
Message Model
    ↓
Structured Gemini Request
    ↓
JSON Serialization
    ↓
Java HttpClient
    ↓
Google Gemini API
    ↓
JSON Response
    ↓
Jackson Deserialization
    ↓
AI Response
    ↓
GuideIn Interface

The project is now entering its next phase:

**turning the functional AI backend into an actual application experience.**

---

# ✦ Current Development

GuideIn is currently being transformed from its original console interface into a **custom Java Swing desktop GUI**.

The new interface is designed around a dark, glossy visual language with muted gold accents and subtle motion.

### Current GUI work includes

* 🖥️ Custom Java Swing desktop interface
* 💬 Chat-style conversation interface
* 🌑 Glossy dark visual design
* ✦ Gold-accented UI components
* 🫧 Animated chat bubble entrance
* ⌁ Smooth message auto-scrolling
* ⋯ Animated AI typing indicator
* 🖱️ Smooth button hover and press animations
* 🟢 Pulsing Gemini status indicator
* ✨ Animated input focus glow
* 🌌 Ambient animated background
* ✦ Slowly drifting background particles
* 🌫️ Subtle breathing/radial background glow
* 🎨 Custom rounded panels and gradients
* 📐 Responsive minimum window sizing
* ⚡ SwingWorker-based asynchronous Gemini requests

The goal is not simply to make the application functional.

The goal is to make **GuideIn feel like an actual product.**

---

# ✦ Interface Direction

GuideIn's interface follows a restrained **glossy-dark / ambient** aesthetic.

```text
╭────────────────────────────────────────────────────╮
│  ✦ GUIDEIN                              ● GEMINI   │
│    AI CAREER MENTOR                                │
├────────────────────────────────────────────────────┤
│                                                    │
│                         ┌──────────────────────┐   │
│                         │ YOU                  │   │
│                         │ How should I learn   │   │
│                         │ backend development? │   │
│                         └──────────────────────┘   │
│                                                    │
│  ┌────────────────────────────────────────────┐    │
│  │ GUIDEIN                                    │    │
│  │ Start with HTTP, REST APIs and Java...     │    │
│  └────────────────────────────────────────────┘    │
│                                                    │
│                                                    │
│  ╭────────────────────────────────────────────╮    │
│  │ Ask about careers, skills, or interview...│ Send│
│  ╰────────────────────────────────────────────╯    │
╰────────────────────────────────────────────────────╯
```

The visual system deliberately avoids excessive motion.

Animations are intended to be:

**slow · soft · subtle · responsive**

rather than distracting.

---

# ✦ Animation System

The GUI is being developed around an **orchestrated layered animation system** using only Java Swing.

No animation framework is currently required.

### Message Animation

New messages fade in while simultaneously sliding upward.

```text
opacity
0% ────────────────→ 100%

position
+16px ─────────────→ 0px
```

This creates a soft conversational flow instead of messages appearing instantly.

### Typing Indicator

The static `Thinking...` state is being replaced with a three-dot animated indicator.

```text
● · ·
· ● ·
· · ●
· ● ·
```

Each dot has a slightly different phase to produce a natural breathing effect.

### Button Interaction

The Send button uses interpolated color transitions for:

* hover
* mouse press
* mouse release
* disabled state

Rather than switching colors immediately, the button eases between visual states.

### Input Focus

The composer receives a subtle gold border glow when the input field gains focus.

### Status Indicator

The Gemini connection indicator uses a small animated green pulse to communicate an active state.

### Ambient Background

The application background is also being animated.

It contains:

* extremely slow radial gold glows
* subtle breathing intensity
* slowly drifting particles
* randomized particle phases
* gentle vertical movement
* low-opacity atmospheric lighting

The background is intentionally kept behind the interface so that the chat remains the visual focus.

---

# ✦ Core Features

### 🤖 Gemini AI Integration

GuideIn communicates with Google's Gemini API through REST requests.

### 💬 Interactive Conversation

The original console interaction is being replaced by a chat-oriented desktop experience.

### 🔌 Native Java HTTP Client

API communication uses Java's built-in `HttpClient`, keeping the network layer lightweight.

### 🧩 Structured Models

Requests and responses are represented through Java model classes rather than manipulating raw JSON throughout the application.

### 📦 Jackson

Jackson handles JSON serialization and deserialization between Java objects and Gemini API payloads.

### 🧵 Asynchronous AI Requests

The Swing GUI uses `SwingWorker` so that Gemini requests do not block the interface while waiting for an API response.

### 🎨 Custom GUI Components

The interface is constructed from custom Swing components including:

* `RoundedPanel`
* `ChatBubble`
* `GradientButton`
* `ChatListPanel`
* `GradientBackgroundPanel`
* `BrandMark`
* `StatusDot`
* `TypingBubble`
* `PlaceholderTextField`
* `DarkScrollBarUI`

---

# ✦ Architecture

The project is intentionally separated into logical layers.

```text
src/
└── main/
    ├── java/
    │   ├── config/
    │   │   └── Config.java
    │   │
    │   ├── model/
    │   │   ├── Candidate.java
    │   │   ├── Content.java
    │   │   ├── GeminiContent.java
    │   │   ├── GeminiRequest.java
    │   │   ├── GeminiResponse.java
    │   │   ├── Message.java
    │   │   ├── Part.java
    │   │   └── Role.java
    │   │
    │   ├── service/
    │   │   ├── AIClient.java
    │   │   └── GUIAIClient.java
    │   │
    │   ├── gui/
    │   │   ├── GuideInFrame.java
    │   │   ├── ChatBubble.java
    │   │   ├── ChatListPanel.java
    │   │   ├── GradientButton.java
    │   │   ├── GradientBackgroundPanel.java
    │   │   ├── RoundedPanel.java
    │   │   ├── TypingBubble.java
    │   │   ├── StatusDot.java
    │   │   ├── DarkScrollBarUI.java
    │   │   └── Decor.java
    │   │
    │   └── Main.java
    │
    └── resources/
```

The architecture is evolving alongside the GUI rather than replacing the original AI/service layer.

---

# ✦ How GuideIn Works

### 01 — User asks a question

A user enters a career-related question into the GuideIn interface.

### 02 — Message creation

The input is converted into a structured `Message` object.

### 03 — Request construction

The service layer constructs a Gemini request using the application's model classes.

### 04 — JSON serialization

Jackson converts the Java request objects into JSON.

### 05 — REST communication

Java's `HttpClient` sends the request to the Gemini API.

### 06 — Response processing

The returned JSON is deserialized into Java response objects.

### 07 — AI response

The generated response is extracted by the service layer.

### 08 — UI rendering

The Swing interface displays the response as an animated GuideIn chat bubble.

```text
┌──────────────┐
│     USER     │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Message    │
│    Model     │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ GUIAIClient  │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Gemini REST  │
│     API      │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   Jackson    │
│ Deserializer │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ GuideIn GUI  │
│ Chat Bubble  │
└──────────────┘
```

---

# ✦ Tech Stack

| Technology            | Purpose                            |
| --------------------- | ---------------------------------- |
| **Java 21**           | Core application language          |
| **Swing**             | Desktop GUI                        |
| **Google Gemini API** | Generative AI                      |
| **Java HttpClient**   | REST communication                 |
| **Jackson**           | JSON serialization/deserialization |
| **Maven**             | Build and dependency management    |
| **Git / GitHub**      | Version control                    |
| **SwingWorker**       | Asynchronous GUI operations        |

---

# ✦ Running GuideIn

## Requirements

* Java 21+
* Maven
* Google Gemini API key

## Configure Gemini

Set your API key as an environment variable.

### PowerShell

```powershell
$env:GEMINI_API_KEY="your_api_key_here"
```

Never commit API credentials directly into the repository.

## Compile

```bash
mvn clean compile
```

## Run

The exact Maven execution command depends on the configured application entry point.

For example:

```bash
mvn exec:java -Dexec.mainClass=Main
```

or:

```bash
mvn exec:java -Dexec.mainClass=gui.GuideInFrame
```

---

# ✦ Project Evolution

GuideIn has gone through several stages.

### Phase I — AI / REST Foundation

The project began as a console-based experiment.

Focus:

* Java fundamentals
* REST APIs
* Gemini integration
* JSON
* HTTP requests
* object-oriented architecture

### Phase II — Structured Application

The project was separated into:

* configuration
* models
* services
* API communication

The goal was to move away from a single-file experiment toward an application architecture.

### Phase III — GUI Integration

The project is now transitioning into a Java Swing desktop application.

Focus:

* conversational interface
* reusable GUI components
* asynchronous requests
* custom styling
* responsive layout

### Phase IV — Motion & Visual Polish

Current development is focused on making the interface feel alive.

Focus:

* layered animations
* smooth transitions
* ambient background motion
* message animations
* typing indicators
* interactive controls
* visual consistency

### Future Direction

```text
Console AI
    ↓
Structured Java Application
    ↓
Desktop AI Interface
    ↓
Polished Career Mentor
    ↓
Context + Memory
    ↓
Career Planning
    ↓
Persistent User Profiles
    ↓
Expanded AI Career Platform
```

---

# ✦ Project Status

<div align="center">

### 🚧 ACTIVE DEVELOPMENT

**The original Gemini backend is functional.
The Swing GUI and visual experience are currently being integrated and polished.**

</div>

### Completed / Established

* [x] Java 21 project foundation
* [x] Maven project configuration
* [x] Gemini API communication
* [x] REST request/response pipeline
* [x] Jackson JSON processing
* [x] Structured model layer
* [x] Service layer
* [x] Original console interaction
* [x] Initial Swing GUI
* [x] Chat-style message interface
* [x] Custom dark/gold visual system
* [x] Rounded UI components
* [x] Asynchronous GUI AI requests
* [x] Animated chat bubble implementation
* [x] Animated button interaction
* [x] Typing indicator implementation
* [x] Pulsing status indicator
* [x] Smooth scrolling implementation
* [x] Input focus animation
* [x] Ambient animated background implementation

### 🔧 Currently Being Integrated

* [ ] Final GUI component integration
* [ ] Resolve remaining GUI compilation issues
* [ ] Verify complete Maven GUI launch flow
* [ ] Fine-tune animation timing
* [ ] Tune background particle/glow intensity
* [ ] Test GUI across different window sizes
* [ ] Final visual polish

### 🔮 Planned

* [ ] Conversation context and memory
* [ ] Persistent conversations
* [ ] Structured career planning
* [ ] User profiles
* [ ] Career roadmap generation
* [ ] Backend API layer
* [ ] Improved error handling
* [ ] Configuration improvements
* [ ] Packaging / distributable desktop build
* [ ] Expanded career-focused AI features

---

# ✦ Why I Built This

I didn't want GuideIn to be another project that simply calls an AI API.

The original goal was to understand what actually happens underneath:

**How does Java communicate with an AI model?**

That question led to learning:

* HTTP
* REST APIs
* JSON
* serialization
* deserialization
* API architecture
* service layers
* object-oriented design
* asynchronous programming

The GUI phase is the next step.

Instead of stopping once the API worked, GuideIn is being pushed toward becoming a **real application with its own identity, interface, and user experience.**

The project is also part of my journey toward becoming stronger in **Java backend development, AI application development, and software architecture**.

---

# ✦ Design Philosophy

GuideIn follows one simple principle:

> **The AI should be powerful. The interface should stay calm.**

That's why the current visual language uses:

```text
Dark surfaces
     +
Muted gold
     +
Soft gradients
     +
Slow ambient motion
     +
Minimal interaction feedback
     =
Calm AI workspace
```

Animations should communicate state and create atmosphere—not distract from the conversation.

---

# ✦ Roadmap

```text
[✓] Gemini API foundation
        │
        ▼
[✓] Java service architecture
        │
        ▼
[✓] Console AI mentor
        │
        ▼
[✓] Swing GUI foundation
        │
        ▼
[✓] Custom visual system
        │
        ▼
[✓] Layered UI animations
        │
        ▼
[~] GUI integration & stabilization
        │
        ▼
[ ] Conversation memory
        │
        ▼
[ ] Career planning engine
        │
        ▼
[ ] Persistent user profiles
        │
        ▼
[ ] Expanded AI career platform
```

---

# ✦ Repository

**GuideIn**
Java + Gemini + Swing

Built as a continuous learning project focused on understanding AI application architecture by actually building one.

---

<div align="center">

### Built by **Sufiyan Khan**

**Learning by building.
Improving by breaking things.
Understanding how everything works under the hood.**

<br>

`Java` · `AI` · `REST APIs` · `Gemini` · `Swing` · `Backend`

</div>
