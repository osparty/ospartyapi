package net.osparty.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test") // use the in-memory FakePartyRepository, not a real Redis
@TestPropertySource(properties = "app.rate-limit.interval-ms=5000")
class RateLimitTest
{
	private static final String BODY =
		"{\"activity\":\"cox\",\"host\":\"Spammer\",\"capacity\":2,\"passphrase\":\"p\"}";

	@Autowired
	private MockMvc mvc;

	@Test
	void secondRapidPostIsRateLimited() throws Exception
	{
		mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isCreated());

		mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void getsAreNotRateLimited() throws Exception
	{
		mvc.perform(get("/api/v1/parties")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/parties")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/parties")).andExpect(status().isOk());
	}
}
