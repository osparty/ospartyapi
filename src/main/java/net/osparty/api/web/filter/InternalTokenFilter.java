package net.osparty.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret gate for {@code /internal/**} (the bot→API badge pushes). Uses the same secret as the
 * API→bot direction ({@code app.discord.internal-token}), so the pair of services shares one token.
 *
 * <p>Unlike the bot's filter (private network, blank token = open for dev), this API is
 * internet-facing — so a blank token REJECTS all internal calls rather than leaving them open. The
 * badge feature simply stays inert until the shared secret is configured on both sides.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {
	private static final String HEADER = "X-Internal-Token";

	private final String expectedToken;

	public InternalTokenFilter(@Value("${app.discord.internal-token:}") String expectedToken) {
		this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/internal/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String provided = request.getHeader(HEADER);
		// Constant-time compare, same as PartyFactory.hostKeyAuthorized.
		if (!expectedToken.isBlank() && provided != null && MessageDigest.isEqual(
			expectedToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(request, response);
		}
		else {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		}
	}
}
