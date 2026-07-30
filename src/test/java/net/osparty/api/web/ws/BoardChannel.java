package net.osparty.api.web.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.osparty.api.transport.Mux;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * A test client for the ad board's half of the merged socket.
 *
 * <p>There is one endpoint now, carrying both protocols, so every frame in both directions begins with a
 * {@link Mux} tag. That is a transport detail the board suites do not otherwise care about — they assert
 * about frames, not bytes — so it lives here rather than five times over.
 */
final class BoardChannel {
	private BoardChannel() {
	}

	/** Open a connection and feed every board frame it receives into {@code messages}. */
	static WebSocketSession connect(int port, ObjectMapper mapper, BlockingQueue<JsonNode> messages)
		throws Exception {
		return new StandardWebSocketClient().execute(
			new AbstractWebSocketHandler() {
				@Override
				protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
					throws Exception {
					byte[] frame = boardPayload(message);
					if (frame != null) {
						messages.add(mapper.readTree(new String(frame, StandardCharsets.UTF_8)));
					}
				}
			},
			"ws://localhost:" + port + "/api/ws").get(5, TimeUnit.SECONDS);
	}

	/** Send one board frame, tagged for its channel. */
	static void send(WebSocketSession session, String json) throws Exception {
		session.sendMessage(frame(json));
	}

	/**
	 * One board frame as it goes on the wire: the channel tag, then the JSON.
	 *
	 * <p>Shaped to drop straight into {@code session.sendMessage(...)} where a {@code new TextMessage(...)}
	 * used to be, so the suites that build their frames inline read the same as they always did.
	 */
	static BinaryMessage frame(String json) {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(payload.length + 1);
		buffer.put(Mux.BOARD).put(payload).flip();
		return new BinaryMessage(buffer);
	}

	/** The board's half of a merged frame, or null when this one belongs to the live party. */
	private static byte[] boardPayload(BinaryMessage message) {
		ByteBuffer payload = message.getPayload();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		if (bytes.length == 0 || bytes[0] != Mux.BOARD) {
			return null;
		}
		return Arrays.copyOfRange(bytes, 1, bytes.length);
	}
}
