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
import net.osparty.api.model.Advertisement;
import net.osparty.api.service.VoiceChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"app.ws.reconcile-interval-ms=150",
	// Every WebSocket is served by Netty on its own port, not by the servlet container's. Zero takes an
	// ephemeral one so the whole suite can run without colliding on 8081.
	"app.socket.port=0"})
class VoiceChannelSocketTest {
	@Autowired
	private net.osparty.api.party.netty.NettySocketServer socketServer;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private StubVoiceChannelService voice;

	@TestConfiguration
	static class Config {
		@Bean
		@org.springframework.context.annotation.Primary
		StubVoiceChannelService stubVoiceChannelService() {
			return new StubVoiceChannelService();
		}
	}

	static class StubVoiceChannelService implements VoiceChannelService {
		final AtomicReference<String> deleted = new AtomicReference<>();
		final AtomicReference<String> renamed = new AtomicReference<>();
		int creates;

		@Override
		public void rename(String channelId, Advertisement ad) {
			renamed.set(channelId);
		}

		@Override
		public synchronized Optional<VoiceChannelInfo> createForParty(Advertisement ad,
			java.util.Collection<String> allowedDiscordIds) {
			creates++;
			return Optional.of(new VoiceChannelInfo("chan-" + ad.getId(),
				"https://discord.gg/stub-" + ad.getId()));
		}

		@Override
		public boolean grantAccess(String channelId, String discordId) {
			return true;
		}

		@Override
		public void revokeAccess(String channelId, String discordId) {
		}

		@Override
		public void delete(String channelId) {
			deleted.set(channelId);
		}

		@Override
		public void disconnectFromChannel(String channelId, String discordId) {
		}
	}

	@Test
	void createVoiceChannelReturnsInviteUrlAndIsIdempotent() throws Exception {
		voice.creates = 0;
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-voice\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsVoice\",\"capacity\":3,\"passphrase\":\"pp-voice\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("ad").path("id").asText();

			session.sendMessage(BoardChannel.frame("{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\"}"));
			JsonNode reply = awaitWhere(messages, m -> "voiceChannel".equals(type(m)), "voiceChannel reply");
			assertThat(reply.path("id").asText()).isEqualTo(id);
			assertThat(reply.path("url").asText()).isEqualTo("https://discord.gg/stub-" + id);
			assertThat(voice.creates).isEqualTo(1);

			session.sendMessage(BoardChannel.frame("{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\"}"));
			JsonNode again = awaitWhere(messages, m -> "voiceChannel".equals(type(m)), "voiceChannel reply (2)");
			assertThat(again.path("url").asText()).isEqualTo("https://discord.gg/stub-" + id);
			assertThat(voice.creates).isEqualTo(1);
		}
		finally {
			session.close();
		}
	}

	@Test
	void transferHostRenamesTheVoiceChannel() throws Exception {
		voice.renamed.set(null);
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-xfer\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"OldHost\",\"capacity\":3,\"passphrase\":\"pp-xfer\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("ad").path("id").asText();

			session.sendMessage(BoardChannel.frame("{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\"}"));
			awaitWhere(messages, m -> "voiceChannel".equals(type(m)), "voiceChannel reply");

			session.sendMessage(BoardChannel.frame("{\"type\":\"transferHost\",\"id\":\"" + id + "\","
				+ "\"host\":\"NewHost\",\"key\":\"k-xfer\",\"newKey\":\"k-new\"}"));
			awaitWhere(messages, m -> "transferred".equals(type(m)), "transferred ack");

			assertThat(voice.renamed.get()).isEqualTo("chan-" + id);
		}
		finally {
			session.close();
		}
	}

	private WebSocketSession connect(BlockingQueue<JsonNode> messages) throws Exception {
		return BoardChannel.connect(socketServer.boundPort(), mapper, messages);
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
