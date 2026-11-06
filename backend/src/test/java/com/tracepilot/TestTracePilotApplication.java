package com.tracepilot;

import org.springframework.boot.SpringApplication;

public class TestTracePilotApplication {

	public static void main(String[] args) {
		SpringApplication.from(TracePilotApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
