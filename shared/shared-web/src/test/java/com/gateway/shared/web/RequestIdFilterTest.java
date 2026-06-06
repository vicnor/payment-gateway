package com.gateway.shared.web;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.shared.web.config.SharedWebAutoConfiguration;
import com.gateway.shared.web.support.EchoController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EchoController.class)
@Import(SharedWebAutoConfiguration.class)
class RequestIdFilterTest {

    @Autowired MockMvc mockMvc;

    private static final String REQ_ID_PATTERN = "req_[0-9A-HJKMNP-TV-Z]{26}";

    @Test
    void everyResponseHasRequestIdHeader() throws Exception {
        String id =
                mockMvc.perform(get("/test/ping"))
                        .andExpect(status().isOk())
                        .andExpect(header().exists("X-Request-Id"))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");

        assertNotNull(id);
        assertTrue(id.matches(REQ_ID_PATTERN), "Expected req_<ULID> but got: " + id);
    }

    @Test
    void eachRequestGetsUniqueId() throws Exception {
        String id1 =
                mockMvc.perform(get("/test/ping"))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");
        String id2 =
                mockMvc.perform(get("/test/ping"))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");
        assertNotEquals(id1, id2, "Consecutive requests must get different request IDs");
    }

    @Test
    void inboundXRequestIdIsIgnored() throws Exception {
        String inbound = "req_AAAAAAAAAAAAAAAAAAAAAAAAAA";
        String returned =
                mockMvc.perform(get("/test/ping").header("X-Request-Id", inbound))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");

        assertNotNull(returned);
        assertNotEquals(
                inbound, returned, "Server must generate its own request-id, not echo inbound");
        assertTrue(
                returned.matches(REQ_ID_PATTERN), "Generated ID should match req_<ULID> pattern");
    }
}
