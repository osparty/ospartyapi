package net.osparty.api.web.rest;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@ActiveProfiles("test")
@TestPropertySource(properties = "app.rate-limit.interval-ms=0")
class PartyApiTest {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void createReturnsTheAdInThePluginFormat() throws Exception {
		String body = "{\"activity\":\"cox\",\"host\":\"Tester\",\"description\":\"trio\","
			+ "\"capacity\":3,\"world\":\"301\",\"minKillCount\":250,\"minHardModeKillCount\":0,"
			+ "\"passphrase\":\"wine-of-zamorak\"}";

		mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").exists())
			.andExpect(jsonPath("$.host").value("Tester"))
			.andExpect(jsonPath("$.passphrase").value("wine-of-zamorak"))
			.andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.members[0]").value("Tester"));
	}

	@Test
	void listFiltersByActivity() throws Exception {
		mvc.perform(get("/api/v1/parties").param("activity", "cox").param("player", "Someone"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.activity != 'cox')]").isEmpty());
	}

	@Test
	void createRejectsMissingHost() throws Exception {
		mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON)
				.content("{\"activity\":\"cox\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void aHostCanOnlyHaveOneAd() throws Exception {
		createParty("UniqueHost", "cox");
		createParty("UniqueHost", "tob");

		mvc.perform(get("/api/v1/parties"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.host == 'UniqueHost')]", hasSize(1)))
			.andExpect(jsonPath("$[?(@.host == 'UniqueHost')].activity", contains("tob")));
	}

	@Test
	void heartbeatKeepsTheAdAndReturnsIt() throws Exception {
		String json = createParty("Beater", "nex");
		String id = objectMapper.readTree(json).get("id").asText();

		mvc.perform(put("/api/v1/parties/" + id + "/heartbeat"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.host").value("Beater"));
	}

	@Test
	void heartbeatUnknownPartyIs404() throws Exception {
		mvc.perform(put("/api/v1/parties/does-not-exist/heartbeat"))
			.andExpect(status().isNotFound());
	}

	@Test
	void hostKeyProtectsKeyedPartiesAndIsNeverReturned() throws Exception {
		String body = "{\"activity\":\"cox\",\"host\":\"Keyed\",\"capacity\":2,\"passphrase\":\"p\"}";
		String json = mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON)
				.header("X-OSParty-Host-Key", "s3cret").content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.hostKey").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String id = objectMapper.readTree(json).get("id").asText();

		mvc.perform(put("/api/v1/parties/" + id + "/heartbeat"))
			.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/parties/" + id + "/heartbeat").header("X-OSParty-Host-Key", "wrong"))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/parties/" + id).header("X-OSParty-Host-Key", "wrong"))
			.andExpect(status().isForbidden());

		mvc.perform(put("/api/v1/parties/" + id + "/heartbeat").header("X-OSParty-Host-Key", "s3cret"))
			.andExpect(status().isOk());
		mvc.perform(delete("/api/v1/parties/" + id).header("X-OSParty-Host-Key", "s3cret"))
			.andExpect(status().isOk());
	}

	@Test
	void deleteUnknownPartyIs404() throws Exception {
		mvc.perform(delete("/api/v1/parties/does-not-exist"))
			.andExpect(status().isNotFound());
	}

	@Test
	void privatePartyHiddenFromListButReachableByCode() throws Exception {
		String body = "{\"activity\":\"toa\",\"host\":\"PrivHost\",\"capacity\":2,\"passphrase\":\"p\","
			+ "\"privateParty\":true,\"lootRule\":\"SPLIT\",\"ironmanOnly\":true,\"hostAccountType\":\"IRONMAN\"}";
		String json = mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.inviteCode").exists())
			.andExpect(jsonPath("$.privateParty").value(true))
			.andExpect(jsonPath("$.lootRule").value("SPLIT"))
			.andExpect(jsonPath("$.ironmanOnly").value(true))
			.andReturn().getResponse().getContentAsString();
		String code = objectMapper.readTree(json).get("inviteCode").asText();

		mvc.perform(get("/api/v1/parties"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.host == 'PrivHost')]", hasSize(0)));

		mvc.perform(get("/api/v1/parties/by-code/" + code))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.host").value("PrivHost"));

		mvc.perform(get("/api/v1/parties/by-code/ZZZZZZ"))
			.andExpect(status().isNotFound());
	}

	private String createParty(String host, String activity) throws Exception {
		String body = "{\"activity\":\"" + activity + "\",\"host\":\"" + host
			+ "\",\"capacity\":2,\"passphrase\":\"p\"}";
		return mvc.perform(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
	}
}
