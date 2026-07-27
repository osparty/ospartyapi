package net.osparty.api.service;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Posts review messages through the Discord bot's {@code /reports} endpoint. */
public class HttpAdReportService implements AdReportService {
	private static final Logger log = LoggerFactory.getLogger(HttpAdReportService.class);

	private final RestClient http;

	public HttpAdReportService(String serviceUrl, String internalToken) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(10));
		RestClient.Builder builder = RestClient.builder().baseUrl(serviceUrl).requestFactory(factory);
		if (internalToken != null && !internalToken.isBlank()) {
			builder = builder.defaultHeader("X-Internal-Token", internalToken);
		}
		this.http = builder.build();
		log.info("Ad-report review messages delegated to {}", serviceUrl);
	}

	@Override
	public Optional<PostedReview> publish(ReviewRequest request) {
		try {
			PostedReview posted = http.post()
				.uri("/reports")
				.body(request)
				.retrieve()
				.body(PostedReview.class);
			if (posted == null || posted.messageId() == null) {
				return Optional.empty();
			}
			return Optional.of(posted);
		}
		catch (Exception e) {
			// The report row is already committed; losing the Discord message is recoverable by
			// querying ad_report for notified = false.
			log.warn("Publishing review for report {} failed: {}", request.reportId(), e.toString());
			return Optional.empty();
		}
	}
}
