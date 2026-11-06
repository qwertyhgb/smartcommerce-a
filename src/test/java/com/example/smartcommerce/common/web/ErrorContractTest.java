package com.example.smartcommerce.common.web;

import com.example.smartcommerce.common.api.BusinessException;
import com.example.smartcommerce.common.api.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorContractTest.TestController.class)
@ExtendWith(OutputCaptureExtension.class)
class ErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationErrorUsesUnifiedContract() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .header(RequestIdFilter.HEADER_NAME, "test-request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "test-request-123"))
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"))
                .andExpect(jsonPath("$.requestId").value("test-request-123"))
                .andExpect(jsonPath("$.details.name").value("name 不能为空"));
    }

    @Test
    void malformedJsonUsesUnifiedContract() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownPathUsesUnifiedContract() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void businessExceptionMapsStatusAndDetails() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ENOUGH"))
                .andExpect(jsonPath("$.details.skuId").value("sku-001"));
    }

    @Test
    void internalErrorDoesNotLeakDetails(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(get("/test/boom")
                        .header(RequestIdFilter.HEADER_NAME, "test-request-500"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "test-request-500"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.requestId").value("test-request-500"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("secret internal detail", "IllegalStateException");
        assertThat(output.getOut()).contains("test-request-500");
    }

    @Test
    void requestIdIsEchoed() throws Exception {
        mockMvc.perform(get("/test/ok").header(RequestIdFilter.HEADER_NAME, "test-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "test-request-123"));
    }

    @Test
    void invalidRequestIdIsReplaced() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ok").header(RequestIdFilter.HEADER_NAME, "bad id!"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER_NAME))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @RestController
    public static class TestController {

        @PostMapping("/test/validate")
        Map<String, Object> validate(@Valid @RequestBody TestRequest request) {
            return Map.of("ok", true);
        }

        @GetMapping("/test/business")
        Map<String, Object> business() {
            throw new BusinessException(ErrorCode.INVENTORY_NOT_ENOUGH, "商品库存不足", Map.of("skuId", "sku-001"));
        }

        @GetMapping("/test/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/test/ok")
        Map<String, Object> ok() {
            return Map.of("ok", true);
        }

        public record TestRequest(@NotBlank(message = "name 不能为空") String name) {
        }
    }
}
