package net.osparty.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DiscordOAuthClient {
	private static final Logger log = LoggerFactory.getLogger(DiscordOAuthClient.class);
	private static final String TOKEN_URL = "https://discord.com/api/oauth2/token";
	private static final String USER_URL = "https://discord.com/api/users/@me";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper mapper;
	private final String clientId;
	private final String clientSecret;
	private final String redirectUri;

	public DiscordOAuthClient(ObjectMapper mapper,
		@Value("${app.discord.oauth.client-id:}") String clientId,
		@Value("${app.discord.oauth.client-secret:}") String clientSecret,
		@Value("${app.discord.oauth.redirect-uri:}") String redirectUri) {
		this.mapper = mapper;
		this.clientId = clientId == null ? "" : clientId.trim();
		this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
		this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
	}

	public Optional<DiscordLinkService.Link> exchangeForUser(String code) {
		try {
			String form = "client_id=" + enc(clientId)
				+ "&client_secret=" + enc(clientSecret)
				+ "&grant_type=authorization_code"
				+ "&code=" + enc(code)
				+ "&redirect_uri=" + enc(redirectUri);
			HttpRequest tokenReq = HttpRequest.newBuilder(URI.create(TOKEN_URL))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();
			HttpResponse<String> tokenResp = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
			if (tokenResp.statusCode() / 100 != 2) {
				log.warn("Discord token exchange failed: HTTP {}", tokenResp.statusCode());
				return Optional.empty();
			}
			String accessToken = mapper.readTree(tokenResp.body()).path("access_token").asText(null);
			if (accessToken == null || accessToken.isBlank()) {
				return Optional.empty();
			}
			HttpRequest userReq = HttpRequest.newBuilder(URI.create(USER_URL))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();
			HttpResponse<String> userResp = http.send(userReq, HttpResponse.BodyHandlers.ofString());
			if (userResp.statusCode() / 100 != 2) {
				log.warn("Discord /users/@me failed: HTTP {}", userResp.statusCode());
				return Optional.empty();
			}
			JsonNode user = mapper.readTree(userResp.body());
			String id = user.path("id").asText(null);
			String username = user.path("username").asText(null);
			if (id == null || id.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(new DiscordLinkService.Link(id, username));
		}
		catch (Exception e) {
			log.warn("Discord OAuth exchange threw: {}", e.toString());
			return Optional.empty();
		}
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
