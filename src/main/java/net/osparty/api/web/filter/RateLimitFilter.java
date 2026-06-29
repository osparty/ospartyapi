package net.osparty.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
	private final long intervalMs;
	private final Map<String, Long> lastAllowed = new ConcurrentHashMap<>();

	public RateLimitFilter(@Value("${app.rate-limit.interval-ms:5000}") long intervalMs) {
		this.intervalMs = intervalMs;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (intervalMs <= 0) {
			chain.doFilter(request, response);
			return;
		}

		long now = System.currentTimeMillis();
		String key = clientKey(request);

		long[] retryAfterMs = {0L};
		lastAllowed.compute(key, (k, last) -> {
			if (last != null && now - last < intervalMs) {
				retryAfterMs[0] = intervalMs - (now - last);
				return last;
			}
			return now;
		});

		if (retryAfterMs[0] > 0) {
			long retrySeconds = Math.max(1, (retryAfterMs[0] + 999) / 1000);
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"rate limited\",\"retryAfterSeconds\":" + retrySeconds + "}");
			return;
		}

		chain.doFilter(request, response);
	}

	private static String clientKey(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
