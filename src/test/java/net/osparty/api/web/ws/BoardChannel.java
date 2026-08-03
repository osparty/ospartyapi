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
import org.springframework.web.socket.TextMessage;
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

	/**
	 * What one received frame may be, matching the server's own frame ceiling.
	 *
	 * <p>The JSR-356 client defaults to 8 KB and answers anything larger by closing the connection with 1009
	 * rather than delivering it, which reads as a server that went quiet. A whole board passes that in a
	 * suite that has hosted a few ads, so without this a test's result depends on how many ads the ones
	 * before it left behind. The plugin has no equivalent limit — this is the harness catching up to the
	 * server, not a constraint the wire has.
	 */
	private static final int MAX_FRAME_BYTES = 64 * 1024;

	/** A client that will accept any frame the server is willing to send. */
	private static StandardWebSocketClient client() {
		jakarta.websocket.WebSocketContainer container =
			jakarta.websocket.ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(MAX_FRAME_BYTES);
		container.setDefaultMaxBinaryMessageBufferSize(MAX_FRAME_BYTES);
		return new StandardWebSocketClient(container);
	}

	/** Open a connection and feed every board frame it receives into {@code messages}. */
	static WebSocketSession connect(int port, ObjectMapper mapper, BlockingQueue<JsonNode> messages)
		throws Exception {
		return client().execute(
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

	/**
	 * The same, on the endpoint the board had to itself before the merge: untagged text frames both ways.
	 *
	 * <p>Kept for plugin 1.0.50, which falls back here when the merged socket has failed it repeatedly.
	 * Goes with that endpoint.
	 */
	static WebSocketSession connectLegacy(int port, ObjectMapper mapper, BlockingQueue<JsonNode> messages)
		throws Exception {
		return client().execute(
			new AbstractWebSocketHandler() {
				@Override
				protected void handleTextMessage(WebSocketSession session, TextMessage message)
					throws Exception {
					messages.add(mapper.readTree(message.getPayload()));
				}
			},
			"ws://localhost:" + port + "/api/v1/ws/parties").get(5, TimeUnit.SECONDS);
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
