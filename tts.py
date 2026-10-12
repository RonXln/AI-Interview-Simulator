import edge_tts
import asyncio
import sys

# 用法: python tts.py "文本内容" output.mp3 "角色代号"
# 例如: python tts.py "Hello" output.mp3 "ProfA"

# 建立角色到 Edge-TTS 音色的映射表
# 音色库参考: 
# Guy (男, 沉稳), Ryan (男, 英音), Jenny (女, 美音), Xiaoxiao (女, 中文), Yunxi (男, 中文)
# 建立角色到 Edge-TTS 音色的映射表
# 阵容：2位考官 + 5位AI假考生
VOICE_MAP = {
    # --- 考官组 ---
    "ProfA": "en-US-GuyNeural",       # 教授A：主导流程，沉稳严肃的美式男声
    "ProfB": "en-GB-SoniaNeural",     # 教授B：专门追问，知性犀利的英式女声

    # --- 假考生组 (5人) ---
    "Alice": "en-US-JennyNeural",     # 假人1：美式年轻女声 (温和派)
    "Bob": "en-US-ChristopherNeural", # 假人2：美式年轻男声 (急躁派)
    "Charlie": "en-US-EricNeural",    # 假人3：美式年轻男声 (数据控)
    "David": "en-GB-RyanNeural",      # 假人4：优雅的英式男声 (逻辑派)
    "Emma": "en-AU-NatashaNeural",    # 假人5：带点口音的澳洲女声 (和事佬)

    # --- 系统兜底 ---
    "System": "en-US-AriaNeural"      # 万一找不到角色，用这个声音兜底
}
async def main():
    # 两种调用形式：
    #   python tts.py --stdin <output.mp3> <character>          # 文本从 stdin 读，UTF-8
    #   python tts.py <text> <output.mp3> <character>            # 兼容旧路径，Windows 下有编码风险
    if len(sys.argv) >= 4 and sys.argv[1] == "--stdin":
        output_file = sys.argv[2]
        character_id = sys.argv[3]
        text = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    elif len(sys.argv) >= 4:
        text = sys.argv[1]
        output_file = sys.argv[2]
        character_id = sys.argv[3]
    else:
        print("Error: Missing arguments.")
        sys.exit(1)

    text = text.strip()
    if not text:
        print("Error: Empty text.")
        sys.exit(2)

    voice = VOICE_MAP.get(character_id, VOICE_MAP["System"])
    print(f"Generating TTS for character: {character_id}, using voice: {voice}")

    communicate = edge_tts.Communicate(text, voice)
    await communicate.save(output_file)

if __name__ == "__main__":
    asyncio.run(main())