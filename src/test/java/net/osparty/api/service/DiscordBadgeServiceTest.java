package net.osparty.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.osparty.api.model.Member;
import net.osparty.api.model.Party;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.assertSame;

class DiscordBadgeServiceTest {

	private DiscordBadgeService service() {
		StringRedisTemplate redis = new StringRedisTemplate();
		ObjectMapper mapper = new ObjectMapper();
		return new DiscordBadgeService(redis, mapper, new DiscordLinkService(redis, mapper, "", ""));
	}

	@Test
	void enrichSkipsRedisWhenNoMemberHasAccountHash() {
		Party p = new Party();
		p.setId("p1");
		p.setMembers(List.of(new Member("Legacy", 0L)));
		List<Party> in = List.of(p);

		assertSame(in, service().enrichParties(in));
	}

	@Test
	void enrichDegradesToNoBadgesWhenRedisUnavailable() {
		Party p = new Party();
		p.setId("p1");
		p.setMembers(List.of(new Member("Host", 42L)));
		List<Party> in = List.of(p);

		assertSame(in, service().enrichParties(in));
	}
}
