demo/                                # 项目根目录
│
├── 📄 配置文件
│   ├── .gitattributes               # Git 属性配置
│   ├── .gitignore                   # Git 忽略规则
│   ├── build.txt                    # 构建信息
│   ├── HELP.md                      # 帮助文档
│   └── pom.xml                      # Maven 项目配置
│
├── 🔧 构建脚本
│   ├── mvnw                         # Maven 包装器 (Unix/Linux)
│   ├── mvnw.cmd                     # Maven 包装器 (Windows)
│   └── tts.py                       # 文本转语音脚本 (Python)
│
├── 📁 .mvn/                         # Maven 包装器配置目录
│   └── wrapper/
│       └── └── maven-wrapper.properties
│
├── 📁 libs/                         # 本地依赖库
│   ├── 📚 sherpa-onnx-native-lib-win-x64-v1.12.32.jar   # Sherpa ONNX 原生库
│   └── 📚 sherpa-onnx-v1.12.32.jar                      # Sherpa ONNX 核心库
│
├── 📁 models/                       # AI 模型文件目录
│   └── sherpa-onnx-streaming-zipformer-en-2023-02-21/
│       ├── 🧠 decoder-epoch-99-avg-1.int8.onnx   # 解码器模型
│       ├── 🧠 encoder-epoch-99-avg-1.int8.onnx   # 编码器模型
│       ├── 🧠 joiner-epoch-99-avg-1.int8.onnx    # 连接器模型
│       └── 📝 tokens.txt                           # 词表文件
│
├── 📁 src/                          # 源代码目录
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── example/
│   │   │           └── demo/
│   │   │               ├── 🚀 DemoApplication.java        # Spring Boot 启动类
│   │   │               ├── 🎯 ASRService.java             # 语音识别服务
│   │   │               │
│   │   │               ├── 📁 config/                     # 配置类目录
│   │   │               │   └── ⚙️ WebSocketConfig.java    # WebSocket 配置
│   │   │               │
│   │   │               ├── 📁 handler/                    # 处理器目录
│   │   │               │   └── 🎙️ AudioHandler.java      # 音频处理器
│   │   │               │
│   │   │               └── 📁 service/                    # 业务服务目录
│   │   │                   └── 🤖 LLMService.java         # 大语言模型服务
│   │   │
│   │   └── resources/
│   │       └── ⚙️ application.properties                  # 应用配置文件
│   │
│   └── test/                        # 测试代码目录
│       └── java/
│           └── com/
│               └── example/
│                   └── demo/
│                       └── ✅ DemoApplicationTests.java   # 单元测试
│
└── 📄 structure.md                  # 项目结构文档 (本文件)
