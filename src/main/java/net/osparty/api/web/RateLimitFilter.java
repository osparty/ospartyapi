package net.osparty.api.web;

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

/**
 * Naive per-client rate limit on <b>POST</b> requests only: at most one every
 * {@code interval-ms} (default 5000 = 1 request / 5s) per client IP. Reads
 * (GET) and deletes (DELETE) are not limited. Exceeding it returns
 * {@code 429 Too Many Requests} with a {@code Retry-After} header.
 *
 * <p>State is in-memory and unbounded — fine for a single small instance; put a
 * real limiter or an API gateway in front if you scale out. Set the interval to
 * {@code 0} to disable (the tests do this).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter
{
	private final long intervalMs;
	private final Map<String, Long> lastAllowed = new ConcurrentHashMap<>();

	public RateLimitFilter(@Value("${app.rate-limit.interval-ms:5000}") long intervalMs)
	{
		this.intervalMs = intervalMs;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request)
	{
		// Only throttle writes (POST); leave GET/DELETE unlimited.
		return !"POST".equalsIgnoreCase(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException
	{
		if (intervalMs <= 0)
		{
			chain.doFilter(request, response);
			return;
		}

		long now = System.currentTimeMillis();
		String key = clientKey(request);

		// Atomically decide+record so two concurrent requests can't both pass.
		long[] retryAfterMs = {0L};
		lastAllowed.compute(key, (k, last) ->
		{
			if (last != null && now - last < intervalMs)
			{
				retryAfterMs[0] = intervalMs - (now - last);
				return last; // reject; keep the existing window
			}
			return now; // allow; open a new window
		});

		if (retryAfterMs[0] > 0)
		{
			long retrySeconds = Math.max(1, (retryAfterMs[0] + 999) / 1000);
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"rate limited\",\"retryAfterSeconds\":" + retrySeconds + "}");
			return;
		}

		chain.doFilter(request, response);
	}

	/** Prefer the forwarded client IP (behind a proxy), else the socket address. */
	private static String clientKey(HttpServletRequest request)
	{
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank())
		{
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
