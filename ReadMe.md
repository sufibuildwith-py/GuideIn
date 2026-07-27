# GuideIn

### AI-Powered Career Guidance Assistant

GuideIn is a Java-based AI career guidance application that uses the Google Gemini API to provide interactive career-related guidance through a console-based interface.

The project started as an exploration of how a Java application can communicate with a generative AI service through a REST API. I built the initial version from the ground up to understand the complete flow of an AI-powered application — from accepting user input and constructing structured JSON requests to sending HTTP requests, receiving API responses, and deserializing them into Java objects.

## Current Features

* Interactive console-based conversation
* Integration with the Google Gemini API
* REST API communication using Java's built-in `HttpClient`
* Structured request and response models
* JSON serialization and deserialization using Jackson
* Maven-based dependency and build management
* Object-oriented project structure with separate model, service, and configuration layers

## Tech Stack

* **Java 21**
* **Google Gemini API**
* **Java HTTP Client**
* **Jackson**
* **Maven**
* **Git**

## Project Structure

```text
src/
└── main/
    └── java/
        ├── config/
        │   └── Config.java
        │
        ├── model/
        │   ├── Candidate.java
        │   ├── Content.java
        │   ├── GeminiContent.java
        │   ├── GeminiRequest.java
        │   ├── GeminiResponse.java
        │   ├── Message.java
        │   ├── Part.java
        │   └── Role.java
        │
        ├── service/
        │   └── AIClient.java
        │
        └── Main.java
```

## How It Works

1. The user enters a career-related question through the console.
2. The input is converted into a structured message object.
3. A Gemini API request is constructed using Java objects.
4. Jackson serializes the request into JSON.
5. Java's `HttpClient` sends the request to the Gemini REST API.
6. The API response is deserialized back into Java objects.
7. The generated AI response is extracted and displayed to the user.

## Running the Project

### Prerequisites

* Java 21 or higher
* Maven
* A Google Gemini API key

### Configure the API Key

Set your API key as an environment variable:

```powershell
$env:GEMINI_API_KEY="your_api_key_here"
```

The API key should never be committed directly to the repository.

### Compile the Project

```bash
mvn clean compile
```

### Run the Application

```bash
mvn exec:java
```

Type `exit` to end the conversation.

## Project Status

🚧 **Actively evolving**

GuideIn is currently in its initial console-based stage. I am continuing to improve the project as I learn more about backend development, software architecture, and AI application development.

Planned improvements include:

* Improving the overall application architecture
* Adding conversation context and memory
* Introducing structured career planning
* Adding user profiles and persistent data storage
* Developing a backend API
* Building a web-based interface
* Expanding GuideIn into a more complete AI-powered career guidance platform

## Why I Built This

I wanted to move beyond simply calling an AI API and understand what actually happens behind the scenes when a Java application communicates with a generative AI service.

This project is part of my journey of learning Java backend development, REST APIs, software architecture, and AI application development by building something that can continue to grow over time.

---

**Built by Sufiyan Khan**

*Learning by building, improving, and understanding how things work under the hood.*
