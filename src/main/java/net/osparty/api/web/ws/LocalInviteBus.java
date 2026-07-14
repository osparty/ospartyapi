package net.osparty.api.web.ws;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node invite delivery: the target, if online, is connected to this same instance. Used in tests
 * and any deployment without Redis. {@link RedisInviteBus} is the multi-replica implementation.
 */
@Component
@Profile("test")
public class LocalInviteBus implements InviteBus {
	private volatile BiPredicate<String, String> localDelivery = (target, frame) -> false;

	@Override
	public void setLocalDelivery(BiPredicate<String, String> localDelivery) {
		this.localDelivery = localDelivery;
	}

	@Override
	public CompletableFuture<Boolean> dispatch(String normalizedTarget, String invitedFrameJson) {
		return CompletableFuture.completedFuture(localDelivery.test(normalizedTarget, invitedFrameJson));
	}
}
