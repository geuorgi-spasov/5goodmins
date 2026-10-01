package com.fivegoodmins.fivegoodmins;

import org.springframework.boot.SpringApplication;

public class TestFivegoodminsApplication {

	public static void main(String[] args) {
		SpringApplication.from(FivegoodminsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
