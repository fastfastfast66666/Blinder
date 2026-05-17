package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.dto.VoiceCommandRequest;
import com.bishe10.backend.dto.VoiceCommandResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceCommandServiceTests {

    private final VoiceCommandService service = createService();

    @Test
    void fallbackUnderstandsCitySwitch() {
        VoiceCommandResponse response = understand("换到北京");

        assertThat(response.getIntent()).isEqualTo("refresh_city");
        assertThat(response.getParams()).containsEntry("cityName", "北京");
    }

    @Test
    void fallbackUnderstandsIndexedDislike() {
        VoiceCommandResponse response = understand("第三条我不喜欢");

        assertThat(response.getIntent()).isEqualTo("dislike_news");
        assertThat(response.getParams()).containsEntry("index", 3);
    }

    @Test
    void fallbackUnderstandsIndexedPlayback() {
        VoiceCommandResponse response = understand("播一下第二条");

        assertThat(response.getIntent()).isEqualTo("play_news");
        assertThat(response.getParams()).containsEntry("index", 2);
    }

    @Test
    void fallbackUnderstandsVisionRequest() {
        VoiceCommandResponse response = understand("帮我看看前面有什么");

        assertThat(response.getIntent()).isEqualTo("open_vision");
    }

    private VoiceCommandResponse understand(String text) {
        VoiceCommandRequest request = new VoiceCommandRequest();
        request.setText(text);
        request.setPage("home");
        request.setCity("上海");
        request.setNewsCount(5);
        return service.understand(request);
    }

    private static VoiceCommandService createService() {
        Bishe10Properties properties = new Bishe10Properties();
        properties.getLlm().setEnabled(false);
        return new VoiceCommandService(properties, new ObjectMapper());
    }
}
