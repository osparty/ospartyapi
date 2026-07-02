package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.osparty.api.model.Party;
import net.osparty.api.service.VoiceChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Exercises the {@code createVoiceChannel} opcode with a stubbed {@link VoiceChannelService} (no real
 * Discord bot): the host provisions a channel, the reply carries the invite URL, and a second request
 * is idempotent (no second create).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.ws.reconcile-interval-ms=150")
class VoiceChannelSocketTest {
	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private StubVoiceChannelService voice;

	@TestConfiguration
	static class Config {
		// @Primary so it wins over the DisabledVoiceChannelService no-op fallback, which is also
		// present (its @ConditionalOnMissingBean is evaluated before this test bean is registered).
		@Bean
		@org.springframework.context.annotation.Primary
		StubVoiceChannelService stubVoiceChannelService() {
			return new StubVoiceChannelService();
		}
	}

	/** Counts create/delete calls and returns a fixed URL, so the opcode can be tested without Discord. */
	static class StubVoiceChannelService implements VoiceChannelService {
		final AtomicReference<String> deleted = new AtomicReference<>();
		int creates;

		@Override
		public synchronized Optional<VoiceChannelInfo> createForParty(Party party,
			java.util.Collection<String> allowedDiscordIds) {
			creates++;
			return Optional.of(new VoiceChannelInfo("chan-" + party.getId(), "https://discord.gg/stub-" + party.getId()));
		}

		@Override
		public void grantAccess(String channelId, String discordId) {
			// not exercised here
		}

		@Override
		public void delete(String channelId) {
			deleted.set(channelId);
		}

		@Override
		public void disconnectFromChannel(String channelId, String discordId) {
			// not exercised here
		}
	}

	@Test
	void createVoiceChannelReturnsInviteUrlAndIsIdempotent() throws Exception {
		voice.creates = 0;
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-voice\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsVoice\",\"capacity\":3,\"passphrase\":\"pp-voice\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			session.sendMessage(new TextMessage("{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\"}"));
			JsonNode reply = awaitWhere(messages, m -> "voiceChannel".equals(type(m)), "voiceChannel reply");
			assertThat(reply.path("id").asText()).isEqualTo(id);
			assertThat(reply.path("url").asText()).isEqualTo("https://discord.gg/stub-" + id);
			assertThat(voice.creates).isEqualTo(1);

			// Second request must not create a second channel — it echoes the stored URL.
			session.sendMessage(new TextMessage("{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\"}"));
			JsonNode again = awaitWhere(messages, m -> "voiceChannel".equals(type(m)), "voiceChannel reply (2)");
			assertThat(again.path("url").asText()).isEqualTo("https://discord.gg/stub-" + id);
			assertThat(voice.creates).isEqualTo(1);
		}
		finally {
			session.close();
		}
	}

	private WebSocketSession connect(BlockingQueue<JsonNode> messages) throws Exception {
		return new StandardWebSocketClient().execute(
			new TextWebSocketHandler() {
				@Override
				protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
					messages.add(mapper.readTree(m.getPayload()));
				}
			},
			"ws://localhost:" + port + "/api/v1/ws/parties").get(5, TimeUnit.SECONDS);
	}

	private static String type(JsonNode msg) {
		return msg.path("type").asText();
	}

	private JsonNode awaitWhere(BlockingQueue<JsonNode> messages, Predicate<JsonNode> match, String desc)
		throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			JsonNode msg = messages.poll(5, TimeUnit.SECONDS);
			if (msg != null && match.test(msg)) {
				return msg;
			}
		}
		throw new AssertionError("Timed out waiting for " + desc);
	}
}
