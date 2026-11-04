package com.xavier.backend.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rejects a client {@code ?sort=} that is not a list of property paths before any query runs. Spring Data
 * would otherwise refuse such an expression with an {@code InvalidDataAccessApiUsageException}, the same type
 * it uses for genuine programming errors, so the expression cannot be told apart once it reaches the query.
 * Whether the property exists is the repository's call ({@link GlobalExceptionHandler}).
 */
@Component
class SortParameterGuard implements HandlerInterceptor, WebMvcConfigurer {

    private static final String SORT_PARAMETER = "sort";
    private static final Pattern PROPERTY_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    /** Tokens Spring Data reads as options of the preceding properties, not as properties. */
    private static final Set<String> OPTIONS = Set.of("asc", "desc", "ignorecase");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String[] sorts = request.getParameterValues(SORT_PARAMETER);
        if (sorts == null || !(handler instanceof HandlerMethod method) || !takesASort(method)) {
            return true;
        }
        boolean valid = Arrays.stream(sorts)
                .flatMap(sort -> Arrays.stream(sort.split(",")))
                .map(String::strip)
                .filter(token -> !token.isEmpty() && !OPTIONS.contains(token.toLowerCase()))
                .allMatch(token -> PROPERTY_PATH.matcher(token).matches());
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid sort: use property names, optionally followed by asc or desc (sort=code,desc).");
        }
        return true;
    }

    private static boolean takesASort(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .map(MethodParameter::getParameterType)
                .anyMatch(type -> Pageable.class.isAssignableFrom(type) || Sort.class.isAssignableFrom(type));
    }
}
