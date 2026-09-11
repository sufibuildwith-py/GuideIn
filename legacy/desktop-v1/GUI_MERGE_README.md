# GuideIn GUI Add-on

This add-on leaves the original GuideIn console application untouched and adds a separate Swing GUI.

## What was added

- `MainGUI.java` — GUI entry point
- `gui/GuideInFrame.java` — complete dark/luxury interface
- `service/GUIAIClient.java` — asynchronous Gemini client used only by the GUI

## Run

Keep the original GuideIn project files exactly as they are, then copy these files into the matching locations:

```text
src/main/java/
├── MainGUI.java
├── gui/
│   └── GuideInFrame.java
└── service/
    └── GUIAIClient.java
```

The GUI uses the existing:

- `config.Config`
- Gemini model setting
- Jackson dependency
- Java 21
- existing model classes

From the GuideIn project root:

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass=MainGUI
```

Or run `MainGUI` directly from IntelliJ / VS Code.

## API key

The GUI reads the same `Config.API_KEY` value as the existing project. No API key is duplicated in the GUI files.

## Important

The original `Main.java` and `service/AIClient.java` are not replaced. The console application remains available exactly as before.

The GUI client is intentionally separate so the original implementation is preserved.
