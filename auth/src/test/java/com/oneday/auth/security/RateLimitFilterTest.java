package com.oneday.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RateLimitProperties props(boolean enabled) {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(enabled);
        p.setLogin(new RateLimitProperties.Rule(3, Duration.ofMinutes(1)));
        p.setApiKey(new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        return p;
    }

    private MockHttpServletRequest loginReq(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/auth/login");
        r.setRemoteAddr(ip);
        return r;
    }

    @Test
    void disabled_alwaysPassesThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(false));
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 50; i++) {
            filter.doFilter(loginReq("1.1.1.1"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(50)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void login_blocksAfterCapacity_withRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(loginReq("2.2.2.2"), res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(loginReq("2.2.2.2"), blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void login_perIpIsolated() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true));
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 3; i++) {
            filter.doFilter(loginReq("3.3.3.3"), new MockHttpServletResponse(), chain);
        }
        // A different IP still has its full allowance.
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(loginReq("4.4.4.4"), res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void nonMatchingPath_passesThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/auth/health");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void apiKey_throttledPerKey() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true));
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/anything");
            r.addHeader("X-Api-Key", "key-abc");
            filter.doFilter(r, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/anything");
        r.addHeader("X-Api-Key", "key-abc");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(r, res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
        // Only the first 2 (within capacity) reached the chain; the 3rd was blocked.
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
