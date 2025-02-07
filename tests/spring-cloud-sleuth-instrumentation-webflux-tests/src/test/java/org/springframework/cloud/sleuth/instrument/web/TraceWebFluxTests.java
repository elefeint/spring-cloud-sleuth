/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web;

import java.util.List;
import java.util.stream.Collectors;

import brave.Span;
import brave.Tracer;
import brave.sampler.Sampler;
import org.awaitility.Awaitility;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.sleuth.instrument.web.client.TraceWebClientAutoConfiguration;
import org.springframework.cloud.sleuth.util.ArrayListSpanReporter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.BDDAssertions.then;

public class TraceWebFluxTests {

	public static final String EXPECTED_TRACE_ID = "b919095138aa4c6e";

	@Test
	public void should_instrument_web_filter() throws Exception {
		// setup
		ConfigurableApplicationContext context = new SpringApplicationBuilder(
				TraceWebFluxTests.Config.class)
						.web(WebApplicationType.REACTIVE)
						.properties("server.port=0", "spring.jmx.enabled=false",
								"spring.sleuth.web.skipPattern=/skipped",
								"spring.application.name=TraceWebFluxTests",
								"security.basic.enabled=false",
								"management.security.enabled=false")
						.run();
		ArrayListSpanReporter accumulator = context.getBean(ArrayListSpanReporter.class);
		int port = context.getBean(Environment.class).getProperty("local.server.port",
				Integer.class);
		Controller2 controller2 = context.getBean(Controller2.class);
		clean(accumulator, controller2);

		// when
		ClientResponse response = whenRequestIsSent(port);
		// then
		thenSpanWasReportedWithTags(accumulator, response);
		clean(accumulator, controller2);

		// when
		ClientResponse nonSampledResponse = whenNonSampledRequestIsSent(port);
		// then
		thenNoSpanWasReported(accumulator, nonSampledResponse, controller2);
		accumulator.clear();

		// when
		ClientResponse skippedPatternResponse = whenRequestIsSentToSkippedPattern(port);
		// then
		thenNoSpanWasReported(accumulator, skippedPatternResponse, controller2);

		// cleanup
		context.close();
	}

	private void clean(ArrayListSpanReporter accumulator, Controller2 controller2) {
		accumulator.clear();
		controller2.span = null;
	}

	private void thenSpanWasReportedWithTags(ArrayListSpanReporter accumulator,
			ClientResponse response) {
		Awaitility.await()
				.untilAsserted(() -> then(response.statusCode().value()).isEqualTo(200));
		List<zipkin2.Span> spans = accumulator.getSpans().stream()
				.filter(span -> "get /api/c2/{id}".equals(span.name()))
				.collect(Collectors.toList());
		then(spans).hasSize(1);
		then(spans.get(0).name()).isEqualTo("get /api/c2/{id}");
		then(spans.get(0).tags()).containsEntry("mvc.controller.method", "successful")
				.containsEntry("mvc.controller.class", "Controller2");
	}

	private void thenNoSpanWasReported(ArrayListSpanReporter accumulator,
			ClientResponse response, Controller2 controller2) {
		Awaitility.await().untilAsserted(() -> {
			then(response.statusCode().value()).isEqualTo(200);
			then(accumulator.getSpans()).isEmpty();
		});
		then(controller2.span).isNotNull();
		then(controller2.span.context().traceIdString()).isEqualTo(EXPECTED_TRACE_ID);
	}

	private ClientResponse whenRequestIsSent(int port) {
		Mono<ClientResponse> exchange = WebClient.create().get()
				.uri("http://localhost:" + port + "/api/c2/10").exchange();
		return exchange.block();
	}

	private ClientResponse whenRequestIsSentToSkippedPattern(int port) {
		Mono<ClientResponse> exchange = WebClient.create().get()
				.uri("http://localhost:" + port + "/skipped").exchange();
		return exchange.block();
	}

	private ClientResponse whenNonSampledRequestIsSent(int port) {
		Mono<ClientResponse> exchange = WebClient.create().get()
				.uri("http://localhost:" + port + "/api/c2/10")
				.header("X-B3-SpanId", EXPECTED_TRACE_ID)
				.header("X-B3-TraceId", EXPECTED_TRACE_ID).header("X-B3-Sampled", "0")
				.exchange();
		return exchange.block();
	}

	@Configuration
	@EnableAutoConfiguration(exclude = { TraceWebClientAutoConfiguration.class })
	static class Config {

		private static final Logger log = LoggerFactory.getLogger(Config.class);

		@Bean
		WebClient webClient() {
			return WebClient.create();
		}

		@Bean
		Sampler sampler() {
			return Sampler.ALWAYS_SAMPLE;
		}

		@Bean
		ArrayListSpanReporter spanReporter() {
			return new ArrayListSpanReporter() {
				@Override
				public List<zipkin2.Span> getSpans() {
					List<zipkin2.Span> spans = super.getSpans();
					log.info("Reported the following spans: \n\n" + spans);
					return spans;
				}
			};
		}

		@Bean
		Controller2 controller2(Tracer tracer) {
			return new Controller2(tracer);
		}
	}

	@RestController
	static class Controller2 {

		private final Tracer tracer;

		Span span;

		Controller2(Tracer tracer) {
			this.tracer = tracer;
		}

		@GetMapping("/api/c2/{id}")
		public Flux<String> successful(@PathVariable Long id) {
			// #786
			then(MDC.get("X-B3-TraceId")).isNotEmpty();
			this.span = this.tracer.currentSpan();
			return Flux.just(id.toString());
		}

		@GetMapping("/skipped")
		public Flux<String> skipped() {
			Boolean sampled = this.tracer.currentSpan().context().sampled();
			then(sampled).isFalse();
			return Flux.just(sampled.toString());
		}

	}

}
