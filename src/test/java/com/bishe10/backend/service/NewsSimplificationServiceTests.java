package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.dto.NewsSimplificationRequest;
import com.bishe10.backend.dto.NewsSimplificationResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSimplificationServiceTests {

    @Test
    void fallsBackToLocalSimplificationWhenLlmDisabled() {
        Bishe10Properties properties = new Bishe10Properties();
        properties.getLlm().setEnabled(false);
        NewsSimplificationService service = new NewsSimplificationService(properties, new ObjectMapper());

        NewsSimplificationResponse response = service.simplify(new NewsSimplificationRequest(
                "上海",
                List.of(new NewsSimplificationRequest.Item(
                        "a1",
                        "上海重点路口与施工绕行提示",
                        "优先整理斑马线、施工围挡和地铁无障碍电梯等高频出行信息，方便提前规划路线。",
                        "",
                        "上海出行服务",
                        "出行提醒"
                ))
        ));

        assertThat(response.mode()).isEqualTo("fallback");
        assertThat(response.items()).hasSize(1);
        NewsSimplificationResponse.Item item = response.items().get(0);
        assertThat(item.id()).isEqualTo("a1");
        assertThat(item.simplifiedText()).isNotBlank();
        assertThat(item.simplifiedText().codePointCount(0, item.simplifiedText().length()))
                .isLessThanOrEqualTo(30);
    }
}
