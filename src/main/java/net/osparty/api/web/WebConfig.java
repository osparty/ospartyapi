package net.osparty.api.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every REST controller with {@code /api/v1}, so endpoints live under a
 * versioned base path (e.g. {@code /api/v1/parties}). Applied at the MVC layer so
 * it's visible to MockMvc tests too.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer
{
	public static final String API_BASE = "/api/v1";

	@Override
	public void configurePathMatch(PathMatchConfigurer configurer)
	{
		configurer.addPathPrefix(API_BASE, HandlerTypePredicate.forAnnotation(RestController.class));
	}
}
