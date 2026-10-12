[English](README.md) | [简体中文](README.zh-CN.md)

# AI Interview Simulator

A real-time voice interview simulator built with Spring Boot, integrating:

- A WebSocket real-time audio channel
- Alibaba Cloud DashScope speech recognition (ASR)
- Interview workflows powered by the DeepSeek large language model
- Microsoft Edge TTS speech synthesis
- Interview interruption, silence detection, preemption, and continuous-dialogue control
- The locally deployed sherpa-onnx-v1.12.32 auxiliary speech recognition model

It is designed for AI interviewer, AI candidate, and multi-role mock interview scenarios, and supports both one-on-one and group interview-style conversations.

<img width="2728" height="1558" alt="fe00271b00cb27eac9e000e4edfa53b3" src="https://github.com/user-attachments/assets/c721159e-1837-4a13-9876-3cd2b50adf1a" />

## Project Overview

The project simulates real interview scenarios in which users interact with the system through audio. The system:

1. Receives microphone audio.
2. Recognizes the user's speech in real time.
3. Sends the recognition results to an LLM to generate the next interview response.
4. Generates and plays speech through TTS, which voices the interviewer or other candidates based on instructions from the backend.
5. Uses the conversation context to determine whether to interrupt, switch questions, or end the interview.
6. Provides feedback on language performance and fluency when the session ends.

## Technology Stack

- Java 17
- Spring Boot 3.2.4
- Spring WebSocket
- Maven
- DeepSeek API
- DashScope Paraformer-Realtime-v2 ASR
- Python + Edge TTS
- Local ONNX models for the speech pipeline

## Directory Structure

```text
.
├── libs/
│   └── sherpa-onnx-v1.12.32.jar
├── models/
│   └── sherpa-onnx-streaming-zipformer-en-2023-02-21/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/demo/
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/
│   └── test/
├── .env.example
├── pom.xml
├── tts.py
├── mvnw
├── mvnw.cmd
└── README.md
```

## Prerequisites

### 1. Install Java

Make sure JDK 17 is installed. OpenJDK 17 or Temurin 17 is recommended.

```bash
java -version
```

### 2. Install Python and its dependencies

The project's TTS relies on the Python script `tts.py` and requires `edge-tts`.

```bash
python -m pip install edge-tts
```

If you want to use a different Python interpreter, set `TTS_PYTHON_CMD` in `.env`.

### 3. Configure environment variables

Copy the example file:

```bash
copy .env.example .env
```

Then add your actual keys and configuration:

```env
DEEPSEEK_API_KEY=your_deepseek_key
DEEPSEEK_API_URL=https://api.deepseek.com/chat/completions
DEEPSEEK_MODEL=deepseek-chat

DASHSCOPE_API_KEY=your_dashscope_key
DASHSCOPE_WS_URL=wss://your-workspace-url/api-ws/v1/inference
ASR_MODEL=paraformer-realtime-v2

TTS_PYTHON_CMD=python
TTS_SCRIPT_PATH=tts.py
```

> Note: The project uses Spring Dotenv to load the `.env` file automatically. If you do not want to use `.env`, you can provide the same values directly as environment variables.



## Download the Local Sherpa-ONNX Model

The entire `models/` directory is ignored by `.gitignore`, so you need to download the model after cloning the repository. The model used by the project is the Sherpa-ONNX streaming English Zipformer:

- Official model archive: [sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2)
- Official model documentation: [Streaming Zipformer models](https://k2-fsa.github.io/sherpa/onnx/streaming/zipformer-models.html)

Download the complete archive and extract it into the `models/` directory at the project root. Windows PowerShell (requires `tar`, which is usually included with Windows 10/11):

```powershell
New-Item -ItemType Directory -Force models | Out-Null
$archive = Join-Path $env:TEMP 'sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2'
Invoke-WebRequest -Uri 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2' -OutFile $archive
tar -xjf $archive -C models
```

Linux or macOS:

```bash
mkdir -p models
curl -L 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2' -o /tmp/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2
tar -xjf /tmp/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2 -C models

```

After extraction, the directory should have this structure (there should not be an extra nested directory with the same name):

```text
models/
└── sherpa-onnx-streaming-zipformer-en-2023-02-21/
	├── decoder-epoch-99-avg-1.int8.onnx
	├── encoder-epoch-99-avg-1.int8.onnx
	├── joiner-epoch-99-avg-1.int8.onnx
	└── tokens.txt
```

**Runtime note:** The current `/ws/audio` and `/ws/asr-test` endpoints primarily use the free DashScope `paraformer-realtime-v2` service for speech recognition. To use speech recognition with the current application, configure a valid `DASHSCOPE_API_KEY` and ensure network access. Sherpa-ONNX was the primary recognition model during the project's early development and now serves as an auxiliary model; it is not required to use the project. See “Prerequisites” and “Start the Application” for the remaining environment variables and startup instructions.

## Start the Application

Run the following command from the project root:

```bash
./mvnw spring-boot:run
```

On Windows PowerShell, use:

```powershell
mvnw.cmd spring-boot:run
```

By default, the application listens at:

```text
http://localhost:8081
```

## WebSocket Endpoints

The project registers two WebSocket endpoints:

- `/ws/audio`: The main interview channel. Receives real-time audio and runs ASR + LLM + TTS.
- `/ws/asr-test`: An ASR-only test channel for recognition testing without LLM or TTS.

## Main Configuration

The configuration file is located at:

- `src/main/resources/application.properties`

It contains settings including:

- `server.port=8081`
- LLM settings under `deepseek.*`
- ASR settings under `dashscope.*` and `asr.*`
- Speech-pause and interruption detection parameters
- Silence-detection thresholds for group and one-on-one interviews
- Speculative LLM and interruption mechanism parameters

## Core Modules

### `LLMService`

Communicates with the DeepSeek API to generate interviewer and candidate responses, and maintains conversation history for each session.

### `ASRService`

Builds DashScope ASR parameters and controls the recognition model, language, silence threshold, and related settings.

### `AudioHandler`

Processes the real-time audio stream, sends it to ASR, and drives the LLM and TTS after recognition is complete.

### `tts.py`

This Python script uses Edge TTS to generate speech for roles such as:

- `ProfA`
- `ProfB`
- `Alice`
- `Bob`
- `Charlie`
- `David`
- `Emma`

## Typical Workflow

1. A browser or client connects to `/ws/audio`.
2. Voice input begins.
3. ASR recognizes the user's speech in real time.
4. The recognition results are sent to the LLM.
5. The LLM generates a response based on the interview flow.
6. The response is converted to speech and played.
7. The cycle continues until the interview ends or is interrupted.

## FAQ

### 1. Startup reports `DASHSCOPE_API_KEY` is not configured

Check that `DASHSCOPE_API_KEY` is set in `.env` or provided as a system environment variable.

### 2. TTS generation fails

Make sure it is installed:

```bash
python -m pip install edge-tts
```

Also verify that `TTS_PYTHON_CMD` and `TTS_SCRIPT_PATH` are configured correctly.

### 3. Speech recognition results are unsatisfactory

You can adjust:

- `asr.max-sentence-silence`
- `asr.language`
- `interrupt.*`
- `silence.*`

These parameters are all in `application.properties`.

## Other Notes

- This project is experimental and intended for AI voice interviews and real-time human-computer interaction prototypes.
- The code includes substantial interview-control logic, such as speaking cadence, interruption budgets, and silence handling. These features are experimental, and various issues remain (for example, the frontend may play multiple audio streams at once or fail to play audio at the expected time; a queue conflict is suspected and is still being investigated).
- The project uses high-frequency input and output chunking to try to reduce latency, but latency can still fluctuate significantly when the context becomes too complex.
