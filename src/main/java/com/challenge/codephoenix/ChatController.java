package com.challenge.codephoenix;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

	private final ChatClient chatClient;

	public ChatController(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.build();
	}

	@GetMapping("/chat")
	public String chat(@RequestParam String mensaje) {
		return chatClient.prompt()
				.user(mensaje)
				.call()
				.content();
	}

}
