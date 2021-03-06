/*
 * Copyright 2015 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.springframework.cloud.netflix.metrics.spectator;

import static org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;

import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.sandbox.BucketFunction;
import com.netflix.spectator.sandbox.BucketFunctions;
import com.netflix.spectator.sandbox.BucketTimer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import com.netflix.spectator.api.Registry;

/**
 * Intercepts incoming HTTP requests and records metrics about execution time and results.
 *
 * @author Taylor Wicksell
 * @author Jon Schneider
 */
public class SpectatorHandlerInterceptor extends HandlerInterceptorAdapter {
	@Value("${netflix.spectator.rest.metricName:rest}")
	String metricName;

	@Value("${netflix.spectator.rest.callerHeader:#{null}}")
	String callerHeader;

	@Value("${netflix.spectator.rest.maxAge:10000}")
	Long maxAge;

	@Autowired
	Registry registry;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		RequestContextHolder.getRequestAttributes().setAttribute("requestStartTime", registry.clock().monotonicTime(), SCOPE_REQUEST);
		return super.preHandle(request, response, handler);
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
		RequestContextHolder.getRequestAttributes().setAttribute("exception", ex, SCOPE_REQUEST);
		Long startTime = (Long) RequestContextHolder.getRequestAttributes().getAttribute("requestStartTime", SCOPE_REQUEST);
		if (startTime != null)
			recordMetric(request, response, handler, startTime);
		super.afterCompletion(request, response, handler, ex);
	}

	protected void recordMetric(HttpServletRequest request, HttpServletResponse response, Object handler, Long startTime) {
		// transform paths like /foo/bar/{user} to foo_bar_-user- because Atlas does everything with query params without escaping
		// the metric name
		String uri = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
				.toString().substring(1).replaceAll("/", "_").replaceAll("[{}]", "-");

		String caller = "unknown";
		if(callerHeader != null)
			caller = request.getHeader(callerHeader);

		Object exception = request.getAttribute("exception");
		Id timerId = registry.createId(metricName,
				"method", request.getMethod(),
				"uri", uri.isEmpty() ? "root" : uri,
				"handlerName", handler instanceof HandlerMethod ? ((HandlerMethod) handler).getMethod().getName() : "unknown",
				"caller", caller != null ? caller : "unknown",
				"exceptionType", exception != null ? exception.getClass().getSimpleName() : "none",
				"status", ((Integer) response.getStatus()).toString());
		BucketFunction f = BucketFunctions.latency(maxAge, TimeUnit.MILLISECONDS);
		BucketTimer t = BucketTimer.get(registry, timerId, f);
		t.record(registry.clock().monotonicTime() - startTime, TimeUnit.NANOSECONDS);
	}
}

