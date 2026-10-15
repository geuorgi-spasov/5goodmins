package com.fivegoodmins.fivegoodmins;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} registers every
 * {@code @ConfigurationProperties} type in this package tree, so adding one is
 * a class rather than a class plus a line in
 * {@code @EnableConfigurationProperties} -- a line that is easy to forget and
 * whose absence surfaces as an unsatisfied dependency somewhere else.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FivegoodminsApplication {

	public static void main(String[] args) {
		SpringApplication.run(FivegoodminsApplication.class, args);
	}

}
