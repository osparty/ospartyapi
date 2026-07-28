package net.osparty.api.v2.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.PartySession;

/**
 * A {@link PartySession} backed by a Netty channel.
 *
 * <p>No lock and no send queue, unlike the servlet transport: every write is handed to the channel's event
 * loop, which serialises them by construction. That is most of the point of this transport — the servlet
 * path spends its CPU inside a synchronized send and a per-session decorator, and this one has neither.
 */
final class NettyPartySession implements PartySession {
	private final Channel channel;
	private final String id;
	private final boolean nodeHinted;
	private final LongAdder dropped;

	NettyPartySession(Channel channel, String path, LongAdder dropped) {
		this.channel = channel;
		this.id = channel.id().asLongText();
		this.nodeHinted = path != null && path.startsWith("/n/");
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
	 * down, or one on a link too slow for its party — is skipped rather than buffered. Live state is the one
	 * kind of message where that is the right answer: the next update supersedes the one dropped, and the
	 * alternative is an unbounded queue of stale snapshots holding heap for a peer nobody can reach. The
	 * servlet transport does the same thing by a different route (a send-buffer limit that closes the
	 * session); here the ghost sweep is what eventually removes a client that never recovers.
	 */
	@Override
	public void send(byte[] frame) {
		if (!channel.isActive()) {
			return;
		}
		if (!channel.isWritable()) {
			dropped.increment();
			return;
		}
		// wrappedBuffer, not copiedBuffer: the array came straight from Jackson and nothing else holds it.
		channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame)));
	}

	/** The live party never sends text; present because the ad board will once it shares this transport. */
	@Override
	public void sendText(String json) {
		send(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
