package net.osparty.api.web.ws;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.function.ToIntBiFunction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node coupling: any signed-in connection for an account, if it exists, is connected to this same
 * instance. Used in tests and any deployment without Redis. {@link RedisCouplingBus} is the multi-replica
 * implementation.
 */
@Component
@Profile("test")
public class LocalCouplingBus implements CouplingBus {
	private volatile LongPredicate online = accountHash -> false;
	private volatile ToIntBiFunction<Long, String> deliver = (accountHash, code) -> 0;

	@Override
	public void setLocalHandlers(LongPredicate online, ToIntBiFunction<Long, String> deliver) {
		this.online = online;
		this.deliver = deliver;
	}

	@Override
	public CompletableFuture<Boolean> anyDeviceOnline(long accountHash) {
		return CompletableFuture.completedFuture(online.test(accountHash));
	}

	@Override
	public CompletableFuture<Integer> deliverCode(long accountHash, String code) {
		return CompletableFuture.completedFuture(deliver.applyAsInt(accountHash, code));
	}
}
