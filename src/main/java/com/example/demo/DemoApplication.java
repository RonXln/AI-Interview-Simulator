package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		// Windows PowerShell 默认 GBK；强制 UTF-8 让日志中文别乱码
		System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
		System.setProperty("file.encoding", "UTF-8");
		SpringApplication.run(DemoApplication.class, args);
	}

}
