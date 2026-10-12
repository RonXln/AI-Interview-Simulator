package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class TTSService {

    @Value("${tts.python.cmd}")
    private String pythonCmd;

    @Value("${tts.script.path}")
    private String scriptPath;

    // 把临时文件统一放到 libs/temp 下，便于集中清理；若该目录不可写则回退到系统临时目录
    private static final Path TEMP_DIR = Paths.get(System.getProperty("user.dir"), "libs", "temp");

    /**
     * Spring 启动时清理上次运行残留的 tts_*.mp3，避免越积越多。
     */
    @PostConstruct
    public void cleanupOnStartup() {
        cleanupDir(TEMP_DIR);
        // 兼容旧逻辑：以前把文件放在项目根目录，这里一并清理掉
        cleanupDir(Paths.get(System.getProperty("user.dir")));
    }

    private void cleanupDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("tts_") && name.endsWith(".mp3");
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 个别文件被占用时忽略，不影响启动
                }
            });
        } catch (IOException ignored) {
            // 目录读取失败时静默忽略
        }
    }

    /**
     * 将文本转为语音文件，并读取为 byte[]
     * @param text 要说的内容
     * @param speaker 角色代号 (如 "ProfA", "Alice")
     */
    public byte[] generateSpeech(String text, String speaker) {

        // 如果没传 speaker，给个默认值防止报错
        if (speaker == null || speaker.trim().isEmpty()) {
            speaker = "System";
        }

        // 确保临时目录存在；若创建失败则回退到系统临时目录
        Path tempDir = TEMP_DIR;
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        }

        String filename = "tts_" + UUID.randomUUID() + ".mp3";
        File outputFile = tempDir.resolve(filename).toFile();
        String workDir = System.getProperty("user.dir");

        Process process = null;
        try {
            // Windows CommandLineToArgvW 会按换行/多空格拆参数，需先把 text 压成单行
            String sanitizedText = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
            if (sanitizedText.isEmpty()) {
                System.err.println("[" + speaker + "] TTS 文本为空，跳过生成");
                return null;
            }
            System.out.println("[" + speaker + "] 正在生成语音: " + sanitizedText);

            // Windows 命令行会按系统 ANSI (GBK) 解码 argv，非 ASCII 文本会乱码。改走 stdin 用 UTF-8 传文本。
            ProcessBuilder pb = new ProcessBuilder(
                    pythonCmd,
                    scriptPath,
                    "--stdin",
                    outputFile.getAbsolutePath(),
                    speaker
            );

            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            process = pb.start();

            try (java.io.OutputStream stdin = process.getOutputStream()) {
                stdin.write(sanitizedText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                System.err.println("TTS 进程超时，已强制终止");
                return null;
            }

            int exitCode = process.exitValue();

            if (exitCode == 0 && outputFile.exists()) {
                byte[] audioData = Files.readAllBytes(outputFile.toPath());
                System.out.println("[" + speaker + "] 语音生成成功! 大小: " + audioData.length + " 字节");
                return audioData;
            } else {
                System.err.println("Python 脚本执行失败或未生成文件。退出码: " + exitCode);
                System.err.println("Python 输出:\n" + output);
            }
        } catch (Exception e) {
            System.err.println("TTS 服务异常:");
            e.printStackTrace();
        } finally {
            // 无论成功、失败、超时、异常，都尝试清理临时文件，避免残留
            if (outputFile.exists() && !outputFile.delete()) {
                outputFile.deleteOnExit(); // 删不掉就让 JVM 退出时再删一次
            }
            // 兜底：如果进程还活着，强杀掉，避免孤儿 python 进程把文件继续占着
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return null;
    }
}