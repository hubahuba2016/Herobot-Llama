# HeroBot (Android — Kotlin & Jetpack Compose)

**HeroBot** is a conversational Android assistant built with **Kotlin**, **Jetpack Compose**, and **Material Design 3**, rewritten from the original Java/XML HeroBot-Llama project while preserving its core features and business logic.

## Features

- **Local Knowledge Base (SQLite):** Pre-seeded from `chitchat.txt` and `chatbot_training_data.txt` with exact lookup and custom Q&A training.
- **On-Device NLP Matching:** Text normalization, stopword filtering, term-frequency vectors, and cosine similarity matching supporting English and Indonesian.
- **Local Math Engine:** Solves single-variable linear equations (e.g., `solve 2x + 5 = 15`), trigonometric functions in degrees (`sin`, `cos`, `tan`), percentages, logarithms, and arithmetic expressions via an RPN evaluator, plus concept explanations.
- **Web Search Fallback:** Queries Wikipedia REST summaries, DuckDuckGo Instant Answer API, SearxNG JSON, and Google snippet/link fallback (configurable via parameters).
- **Local Ollama Integration:** Connects to a local Ollama server (`http://localhost:11434/api/generate`) using configurable `model`, `temperature`, `top_p`, and `stream` parameters.
- **Voice Input & Spoken Replies:** Uses Android's `RecognizerIntent` for speech-to-text and `TextToSpeech` for spoken responses (with a mute toggle in the top bar).
- **Custom Training:** Teach HeroBot new responses via the **Train HeroBot** dialog (long-press Send, tap the brain icon, or type `train`) or inline `train: Question | Answer` commands.

## Project Structure

- `app/src/main/kotlin/com/herobot/MainActivity.kt` — Edge-to-edge Compose host, TTS lifecycle, and speech recognizer launcher
- `app/src/main/kotlin/com/herobot/ui/` — `ChatViewModel`, `ChatScreen`, `ChatBubbles`, `TrainDialog`, `ParametersDialog`, and M3 theme
- `app/src/main/kotlin/com/herobot/engine/` — `ChatbotEngine`, `MathEngine`, `NLPHelper`, `WebSearchService`, `OllamaClient`, `ConversationHistory`
- `app/src/main/kotlin/com/herobot/data/` — `DBHelper` SQLite persistence for Q&A pairs and bot parameters
- `app/src/main/kotlin/com/herobot/voice/` — `VoiceAssistant` TTS and STT helpers
- `app/src/test/kotlin/com/herobot/` — Unit tests for NLP, math, and web search logic

## License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see `LICENSE`.
