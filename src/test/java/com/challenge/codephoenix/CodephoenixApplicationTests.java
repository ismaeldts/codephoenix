package com.challenge.codephoenix;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = { "codephoenix.oracle.warm-up=false", "codephoenix.workspace=build/test-workspace" })
class CodephoenixApplicationTests {

	@Test
	void contextLoads() {
	}

}
