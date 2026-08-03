package net.osparty.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.osparty.api.model.Member;
import net.osparty.api.model.Advertisement;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.assertSame;

class DiscordBadgeServiceTest {

	private DiscordBadgeService service() {
		StringRedisTemplate redis = new StringRedisTemplate();
		ObjectMapper mapper = new ObjectMapper();
		net.osparty.api.repository.InMemoryDiscordLinkRepository repository =
			new net.osparty.api.repository.InMemoryDiscordLinkRepository();
		return new DiscordBadgeService(redis, mapper,
			new DiscordLinkService(redis, mapper, repository, "", ""), repository);
	}

	@Test
	void enrichSkipsRedisWhenNoMemberHasAccountHash() {
		Advertisement p = new Advertisement();
		p.setId("p1");
		p.setMembers(List.of(new Member("Legacy", 0L)));
		List<Advertisement> in = List.of(p);

		assertSame(in, service().enrichAds(in));
	}

	@Test
	void enrichDegradesToNoBadgesWhenRedisUnavailable() {
		Advertisement p = new Advertisement();
		p.setId("p1");
		p.setMembers(List.of(new Member("Host", 42L)));
		List<Advertisement> in = List.of(p);

		assertSame(in, service().enrichAds(in));
	}
}
