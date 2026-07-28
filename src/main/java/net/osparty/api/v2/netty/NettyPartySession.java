package net.osparty.api.v2.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.PartySession;

/**
 * A {@link PartySession} backed by a Netty channel, optionally tagged as one channel of a merged connection.
 *
 * <p>No lock and no send queue, unlike the servlet transport: every write is handed to the channel's event
 * loop, which serialises them by construction. That is most of the point of this transport — the servlet
 * path spends its CPU inside a synchronized send and a per-session decorator, and this one has neither.
 *
 * <p>Two of these can share a channel, one per protocol, each prepending its own {@link
 * net.osparty.api.transport.Mux} tag. Neither knows the other exists; the demultiplexer on the read side is
 * what pairs them.
 */
final class NettyPartySession implements PartySession {
	/** No tag: this session owns its channel outright, as on the two single-protocol endpoints. */
	static final byte UNTAGGED = 0;

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

	NettyPartySession(Channel channel, String path, LongAdder dropped, byte tag, boolean lossy) {
		this.channel = channel;
		// Distinct per channel, and per protocol on a merged one: the two handlers keep separate maps, but a
		// shared id would make their log lines impossible to tell apart.
		this.id = tag == UNTAGGED ? channel.id().asLongText() : channel.id().asLongText() + "#" + tag;
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
		// wrappedBuffer, not copiedBuffer: the array came straight from Jackson and nothing else holds it. On
		// a merged connection the tag rides in front of it as a second component, so still nothing is copied.
		channel.writeAndFlush(new BinaryWebSocketFrame(tag == UNTAGGED
			? Unpooled.wrappedBuffer(frame)
			: Unpooled.wrappedBuffer(new byte[] { tag }, frame)));
	}

	/**
	 * A genuine text frame, not bytes that happen to be UTF-8: the ad board answers clients that predate
	 * compression, and those read text. Sending binary to one of them would be silently ignored.
	 *
	 * <p>On a merged connection there is no such client — the endpoint is newer than compression is — and a
	 * text frame has nowhere to put the tag, so this goes out tagged and binary like everything else.
	 */
	@Override
	public void sendText(String json) {
		if (!writable()) {
			return;
		}
		if (tag == UNTAGGED) {
			channel.writeAndFlush(new TextWebSocketFrame(json));
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
