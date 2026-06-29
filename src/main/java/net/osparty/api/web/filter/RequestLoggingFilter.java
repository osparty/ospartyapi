package net.osparty.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger("net.osparty.api.requests");
	private static final int MAX_BODY = 512;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		long start = System.currentTimeMillis();
		ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
		try {
			chain.doFilter(wrapped, response);
		}
		finally {
			long elapsedMs = System.currentTimeMillis() - start;
			String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
			log.info("{} {}{} from {} ua=\"{}\" -> {} ({}ms)",
				request.getMethod(), request.getRequestURI(), query, clientIp(request),
				header(request, "User-Agent"), response.getStatus(), elapsedMs);

			if (log.isDebugEnabled() && hasBody(request)) {
				String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
				if (!body.isEmpty()) {
					log.debug("  body: {}", truncate(body));
				}
			}
		}
	}

	private static boolean hasBody(HttpServletRequest request) {
		String method = request.getMethod();
		return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
	}

	private static String truncate(String body) {
		String collapsed = body.replaceAll("\\s+", " ").trim();
		return collapsed.length() > MAX_BODY
			? collapsed.substring(0, MAX_BODY) + "…(" + collapsed.length() + " bytes)"
			: collapsed;
	}

	private static String header(HttpServletRequest request, String name) {
		String value = request.getHeader(name);
		return value == null ? "-" : value;
	}

	private static String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		return forwarded != null && !forwarded.isBlank()
			? forwarded.split(",")[0].trim() : request.getRemoteAddr();
	}
}
