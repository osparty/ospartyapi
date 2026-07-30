package net.osparty.api.party.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.SocketSession;

/**
 * A {@link SocketSession} backed by a Netty channel, tagged as one channel of the merged connection.
 *
 * <p>No lock and no send queue, unlike the servlet transport: every write is handed to the channel's event
 * loop, which serialises them by construction. That is most of the point of this transport — the servlet
 * path spends its CPU inside a synchronized send and a per-session decorator, and this one has neither.
 *
 * <p>Two of these can share a channel, one per protocol, each prepending its own {@link
 * net.osparty.api.transport.Mux} tag. Neither knows the other exists; the demultiplexer on the read side is
 * what pairs them.
 */
final class NettySocketSession implements SocketSession {
	private final Channel channel;
	private final String id;
	private final boolean nodeHinted;
	private final byte tag;
	/**
	 * Whether a frame may be thrown away when the client is not draining. True for live state, where the next
	 * frame supersedes the one dropped; false for the board, whose frames are deltas that cannot be skipped.
	 */
	private final boolean lossy;
	private final LongAdder dropped;

	NettySocketSession(Channel channel, String path, LongAdder dropped, byte tag, boolean lossy) {
		this.channel = channel;
		// Distinct per protocol as well as per channel: the two handlers keep separate maps, and a shared id
		// would make their log lines impossible to tell apart.
		this.id = channel.id().asLongText() + "#" + tag;
		this.nodeHinted = path != null && path.startsWith("/n/");
		this.tag = tag;
		this.lossy = lossy;
		this.dropped = dropped;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public boolean isOpen() {
		return channel.isActive();
	}

	/**
	 * Queue one frame for this client.
	 *
	 * <p>A channel that has stopped draining — a client that has gone away without the connection being torn
	 * down, or one on a link too slow for its party — has run out of the buffer it was given. What happens
	 * then depends on the channel: a lossy one skips the frame, because the next update supersedes it and the
	 * alternative is an unbounded queue of stale snapshots holding heap for a peer nobody can reach. A board
	 * connection cannot skip, since its frames are deltas, so it is closed instead — exactly what the servlet
	 * transport's send-buffer limit does, and the client resumes from its last sequence on reconnect.
	 */
	@Override
	public void send(byte[] frame) {
		if (!writable()) {
			return;
		}
		// wrappedBuffer, not copiedBuffer: the array came straight from Jackson and nothing else holds it.
		// The tag rides in front of it as a second component, so still nothing is copied.
		channel.writeAndFlush(new BinaryWebSocketFrame(
			Unpooled.wrappedBuffer(new byte[] { tag }, frame)));
	}

	/**
	 * The ad board's uncompressed frames, which it builds as a String rather than as bytes.
	 *
	 * <p>Named for a text frame because that is what it used to be, on the endpoint the board had to itself.
	 * There is nowhere to put a channel tag on one, so on this connection it goes out tagged and binary like
	 * everything else — the payload is identical either way, only the opcode differs.
	 */
	@Override
	public void sendText(String json) {
		if (!writable()) {
			return;
		}
		byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		channel.writeAndFlush(new BinaryWebSocketFrame(
			Unpooled.wrappedBuffer(new byte[] { tag }, payload)));
	}

	/** Whether this frame should be written at all; closes or counts the connection out if not. */
	private boolean writable() {
		if (!channel.isActive()) {
			return false;
		}
		if (channel.isWritable()) {
			return true;
		}
		dropped.increment();
		if (!lossy) {
			channel.close();
		}
		return false;
	}

	@Override
	public void close() {
		channel.close();
	}

	@Override
	public boolean nodeHinted() {
		return nodeHinted;
	}
}
