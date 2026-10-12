# AI Interview Simulator

这是一个基于 Spring Boot 的实时语音面试模拟器，集成了:

- WebSocket 实时音频通道
- 阿里云 DashScope 语音识别（ASR）
- DeepSeek 大语言模型驱动的面试流程
- Microsoft Edge TTS 语音合成
- 面试中断、沉默检测、抢占和连续对话控制
- 本地部署的辅助语音识别模型sherpa-onnx-v1.12.32

它适合用于 AI 面试官 / AI 候选人 / 多角色模拟面试场景，支持单面或群面（小组面试）式对话体验。

<img width="2728" height="1558" alt="fe00271b00cb27eac9e000e4edfa53b3" src="https://github.com/user-attachments/assets/c721159e-1837-4a13-9876-3cd2b50adf1a" />

## 项目概览

该项目主要用于模拟真实面试场景，用户通过音频与系统进行交互，系统会：

1. 接收麦克风音频
2. 实时识别用户语音
3. 把识别结果交给 LLM 生成下一轮面试回应
4. 通过 TTS 生成语音并播放，由TTS根据后台发出的指示信号扮演面试官或其它候选人
5. 根据上下文判断是否需要打断、切换问题、结束面试
6. 流程结束后给出关于语言表现和流畅度表现方面的反馈

## 技术栈

- Java 17
- Spring Boot 3.2.4
- Spring WebSocket
- Maven
- DeepSeek API
- DashScope Paraformer-Realtime-v2 ASR
- Python + Edge TTS
- Local ONNX models for speech pipeline

## 目录结构

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

## 运行前准备

### 1. 安装 Java

确保已安装 JDK 17，建议使用 OpenJDK 17 或 Temurin 17。

```bash
java -version
```

### 2. 安装 Python 及依赖

项目中的 TTS 依赖 Python 脚本 `tts.py`，需要安装 `edge-tts`。

```bash
python -m pip install edge-tts
```

如果需要使用其他 Python 解释器，也可在 `.env` 中配置 `TTS_PYTHON_CMD`。

### 3. 配置环境变量

复制示例文件：

```bash
copy .env.example .env
```

然后填写真实密钥和配置：

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

> 说明：项目使用 Spring Dotenv 自动加载 `.env` 文件；如果不想使用 `.env`，也可以直接在环境变量里注入相同配置。



## 下载本地 Sherpa-ONNX 模型

`models/` 已在 `.gitignore` 中忽略，因此克隆项目后需要自行下载模型。项目使用的模型是 Sherpa-ONNX 英文流式 Zipformer：

- 官方模型归档：[sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2)
- 官方模型说明：[Streaming Zipformer models](https://k2-fsa.github.io/sherpa/onnx/streaming/zipformer-models.html)

下载完整归档并解压到项目根目录的 `models/`。Windows PowerShell（需要系统提供 `tar`，Windows 10/11 通常自带）：

```powershell
New-Item -ItemType Directory -Force models | Out-Null
$archive = Join-Path $env:TEMP 'sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2'
Invoke-WebRequest -Uri 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2' -OutFile $archive
tar -xjf $archive -C models
```

Linux 或 macOS：

```bash
mkdir -p models
curl -L 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2' -o /tmp/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2
tar -xjf /tmp/sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2 -C models
```

解压后应能看到以下结构（不要多嵌套一层同名目录）：

```text
models/
└── sherpa-onnx-streaming-zipformer-en-2023-02-21/
	├── decoder-epoch-99-avg-1.int8.onnx
	├── encoder-epoch-99-avg-1.int8.onnx
	├── joiner-epoch-99-avg-1.int8.onnx
	└── tokens.txt
```

**运行说明：**当前版本的 `/ws/audio` 和 `/ws/asr-test` 使用 DashScope `paraformer-realtime-v2` 进行语音识别，代码尚未加载上述本地 ONNX 模型。因此，下载模型是为项目保留的本地模型文件准备步骤，并不是当前启动应用的必要条件；要让当前语音识别正常工作，仍需配置有效的 `DASHSCOPE_API_KEY` 和可用网络。其余环境变量及启动方式见上文“运行前准备”和“启动项目”。

## 启动项目

在项目根目录执行：

```bash
./mvnw spring-boot:run
```

Windows PowerShell 下可使用：

```powershell
mvnw.cmd spring-boot:run
```

启动后，应用默认监听：

```text
http://localhost:8081
```

## WebSocket 接口

项目注册了两个 WebSocket 端点：

- `/ws/audio`：主面试通道，接收实时音频流并进行 ASR + LLM + TTS
- `/ws/asr-test`：纯 ASR 测试通道，用于识别测试而不进 LLM/TTS

## 主要配置项

配置文件位于：

- `src/main/resources/application.properties`

这些配置项包括：

- `server.port=8081`
- LLM 的 `deepseek.*`
- ASR 的 `dashscope.*` 和 `asr.*`
- 语音暂停/打断检测相关参数
- group / single 面试的静默检测阈值
- speculative LLM 和 interrupt 机制参数

## 核心模块说明

### `LLMService`

负责与 DeepSeek API 通信，生成面试官 / 候选人回应，并维护每个会话的上下文历史。

### `ASRService`

负责构建 DashScope ASR 参数，控制识别模型、语言、静默阈值等。

### `AudioHandler`

处理实时音频数据流，负责将音频流送入 ASR，并在识别完成后驱动 LLM 和 TTS。

### `tts.py`

Python 脚本，通过 Edge TTS 生成各个角色的语音，例如：

- `ProfA`
- `ProfB`
- `Alice`
- `Bob`
- `Charlie`
- `David`
- `Emma`

## 典型使用流程

1. 浏览器或客户端连接 `/ws/audio`
2. 语音输入开始
3. ASR 实时识别用户话语
4. 识别结果发送给 LLM
5. LLM 根据面试流程生成回复
6. 回复被转成语音并播放
7. 继续迭代直到面试结束或被中断

## 常见问题

### 1. 启动时报 `DASHSCOPE_API_KEY 未配置`

检查 `.env` 中是否已填入 `DASHSCOPE_API_KEY`，或确保系统环境变量已注入。

### 2. TTS 生成失败

确认已安装：

```bash
python -m pip install edge-tts
```

并确认 `TTS_PYTHON_CMD` 和 `TTS_SCRIPT_PATH` 配置正确。

### 3. 语音识别效果不理想

可以调整：

- `asr.max-sentence-silence`
- `asr.language`
- `interrupt.*`
- `silence.*`

这些参数都集中在 `application.properties` 中。

## 其它

- 本项目偏实验性和演示型，适合 AI 语音面试、实时人机交互原型验证。
- 代码中包含较多面试控制逻辑，如发言节奏、打断预算、沉默处理等，但皆为实验性功能，目前实际运行中仍有诸多问题待解决（如前端可能会多个音频同时发声或未在指定时间发声，推测是队列冲突问题，目前仍在排查）
- 采用了高频率切片的流式输入和输出设计来尝试降低延迟，但仍可能在上下文过于复杂时遇到延迟大幅度波动情况
