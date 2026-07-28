package net.osparty.api.v2.netty;

import net.osparty.api.transport.PartySession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.v2.PartyV2FrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Serves the Party V2 live socket from Netty, on its own port, when
 * {@code app.party-v2.transport=netty}.
 *
 * <p><b>Why a second server at all.</b> Profiling put roughly half of this service's CPU inside Tomcat's
 * WebSocket send — a synchronized write path plus a per-session decorator, entered once per recipient per
 * frame (PARTY_V2_OPTIMIZATION.md §6.5.3). Nothing above the transport had to change to try the
 * alternative: rooms, ownership, placement and the protocol all sit behind {@code PartySession} and
 * {@link PartyV2FrameHandler}, so this class only carries bytes. The V1 ad board and every REST endpoint
 * stay on the servlet container's port, which is also why this is a second server rather than a migration.
 *
 * <p><b>Shutdown order matters.</b> {@code PartyV2Heartbeat} drains owned rooms at
 * {@code Integer.MAX_VALUE}, and Spring stops the highest phase first — so this must sit below it, or the
 * drain frames would be written to sockets that are already closed. That is the same mistake, in the same
 * place, that {@code @PreDestroy} made before the heartbeat became a {@link SmartLifecycle}.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.transport", havingValue = "netty")
public class NettyPartyV2Server implements SmartLifecycle {
	private static final Logger log = LoggerFactory.getLogger(NettyPartyV2Server.class);

	/** Below the heartbeat's drain, above everything that does not have sockets to flush. */
	private static final int PHASE = Integer.MAX_VALUE - 1000;

	/** Frames are hundreds of bytes; this is generous for anything a client legitimately sends. */
	private static final int MAX_FRAME_BYTES = 64 * 1024;
	private static final int MAX_HTTP_BYTES = 8 * 1024;

	/**
	 * A channel stops being writable above the high mark and becomes writable again below the low one.
	 * Sized to hold a healthy burst — a roster plus a few update rounds — and no more: past that, a live
	 * update is better dropped than queued (see {@link NettyPartySession#send}).
	 */
	private static final WriteBufferWaterMark WATER_MARK =
		new WriteBufferWaterMark(256 * 1024, 512 * 1024);

	private final PartyV2FrameHandler frames;
	private final int port;
	/** Live updates skipped because a client was not draining its socket. Reported at shutdown. */
	private final LongAdder dropped = new LongAdder();

	private volatile EventLoopGroup boss;
	private volatile EventLoopGroup workers;
	private volatile Channel serverChannel;
	private volatile boolean running;

	public NettyPartyV2Server(PartyV2FrameHandler frames,
		@Value("${app.party-v2.port:8081}") int port) {
		this.frames = frames;
		this.port = port;
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		boss = new NioEventLoopGroup(1);
		// Default sizing: two loops per core, each serving many connections. Sends run on the loop that owns
		// the channel, so this is the pool the fan-out actually costs.
		workers = new NioEventLoopGroup();
		PartyV2NettyHandler handler = new PartyV2NettyHandler(frames, dropped);
		WebSocketServerProtocolConfig ws = WebSocketServerProtocolConfig.newBuilder()
			// The path is checked by PartyV2PathFilter, which has to see it anyway to reject anything else:
			// two forms are served, /api/v2/ws/party and the node-hinted /n/{nodeId}/api/v2/ws/party, and no
			// single prefix covers both.
			.websocketPath("/")
			.checkStartsWith(true)
			.maxFramePayloadLength(MAX_FRAME_BYTES)
			.handleCloseFrames(true)
			.build();
		ServerBootstrap bootstrap = new ServerBootstrap()
			.group(boss, workers)
			.channel(NioServerSocketChannel.class)
			.childOption(ChannelOption.TCP_NODELAY, true)
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel channel) {
					channel.pipeline()
						.addLast(new HttpServerCodec())
						.addLast(new HttpObjectAggregator(MAX_HTTP_BYTES))
						.addLast(new PartyV2PathFilter())
						.addLast(new WebSocketServerProtocolHandler(ws))
						// Clients do not fragment, but a fragmented frame that arrived unassembled would be
						// silently dropped rather than parsed, which is a bad way to find out.
						.addLast(new WebSocketFrameAggregator(MAX_FRAME_BYTES))
						.addLast(handler);
				}
			});
		try {
			serverChannel = bootstrap.bind(port).sync().channel();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted binding the Party V2 Netty server", e);
		}
		running = true;
		log.info("Party V2 Netty transport listening on {}", port);
	}

	@Override
	public void stop() {
		if (!running) {
			return;
		}
		running = false;
		if (serverChannel != null) {
			serverChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
		}
		// The drain has already run by now (higher phase), so this is closing sockets whose members have
		// been told where to go.
		workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
		boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
		log.info("Party V2 Netty transport stopped ({} updates dropped to unwritable channels)", dropped.sum());
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** The port actually bound, which is the configured one unless it was 0 (tests take an ephemeral port). */
	public int boundPort() {
		Channel channel = serverChannel;
		if (channel != null && channel.localAddress() instanceof java.net.InetSocketAddress address) {
			return address.getPort();
		}
		return port;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}
}
