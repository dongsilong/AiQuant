package org.dromara.quant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;

/**
 * 将量化模块当前时间展示时区告知前端，供统一时间格式化器使用。
 */
@Component
@RequiredArgsConstructor
public class QuantTimeZoneFilter extends OncePerRequestFilter {

    private final QuantProperties quantProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI().substring(contextPath.length());
        return !("/quant".equals(requestPath) || requestPath.startsWith("/quant/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String timeZone = ZoneId.of(quantProperties.getTimeZone()).getId();
        response.setHeader("X-Quant-Time-Zone", timeZone);
        response.addHeader("Access-Control-Expose-Headers", "X-Quant-Time-Zone");
        filterChain.doFilter(request, response);
    }
}
