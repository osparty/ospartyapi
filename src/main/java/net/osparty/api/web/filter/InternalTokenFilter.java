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
		if (!expectedToken.isBlank() && provided != null && MessageDigest.isEqual(
			expectedToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(request, response);
		}
		else {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		}
	}
}
