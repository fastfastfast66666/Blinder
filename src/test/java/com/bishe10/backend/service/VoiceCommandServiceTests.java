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

    @Test
    void fallbackTreatsOpenNewsPageAsHomeNavigation() {
        VoiceCommandResponse response = understand("打开资讯界面");

        assertThat(response.getIntent()).isEqualTo("go_home");
        assertThat(response.getParams()).isEmpty();
    }

    @Test
    void fallbackTreatsNewsReadingAsPlayback() {
        VoiceCommandResponse response = understand("听新闻");

        assertThat(response.getIntent()).isEqualTo("play_news");
    }

    @Test
    void fallbackTreatsWantToBrowseNewsAsHomeNavigation() {
        VoiceCommandResponse response = understand("我想看看新闻");

        assertThat(response.getIntent()).isEqualTo("go_home");
    }

    @Test
    void fallbackTreatsWantToListenNewsAsPlayback() {
        VoiceCommandResponse response = understand("我想听听新闻");

        assertThat(response.getIntent()).isEqualTo("play_news");
    }

    @Test
    void fallbackOpensIndexedNewsWhenUserWantsToLookAtOneItem() {
        VoiceCommandResponse response = understand("我想看看第二条新闻");

        assertThat(response.getIntent()).isEqualTo("read_detail");
        assertThat(response.getParams()).containsEntry("index", 2);
    }

    @Test
    void fallbackDoesNotConfuseNewsCenterWithProfile() {
        VoiceCommandResponse response = understand("打开新闻中心");

        assertThat(response.getIntent()).isEqualTo("go_home");
    }

    @Test
    void fallbackKeepsProfileNavigation() {
        VoiceCommandResponse response = understand("打开个人中心");

        assertThat(response.getIntent()).isEqualTo("go_profile");
    }

    @Test
    void fallbackSwitchesBetweenTopLevelPages() {
        assertThat(understandOnPage("打开资讯界面", "vision").getIntent()).isEqualTo("go_home");
        assertThat(understandOnPage("切换到识图界面", "home").getIntent()).isEqualTo("open_vision");
        assertThat(understandOnPage("打开我的个人界面", "home").getIntent()).isEqualTo("go_profile");
    }

    @Test
    void fallbackKeepsNewsActionsOnHomePage() {
        VoiceCommandResponse refresh = understandOnPage("刷新城市新闻", "home");
        VoiceCommandResponse skip = understandOnPage("跳过第三条新闻", "home");

        assertThat(refresh.getIntent()).isEqualTo("refresh_city");
        assertThat(skip.getIntent()).isEqualTo("skip_news");
        assertThat(skip.getParams()).containsEntry("index", 3);
    }

    @Test
    void fallbackDoesNotRunNewsActionsOnVisionPage() {
        VoiceCommandResponse response = understandOnPage("刷新城市新闻", "vision");

        assertThat(response.getIntent()).isEqualTo("unknown");
    }

    @Test
    void fallbackDoesNotSwitchCityOutsideHomePage() {
        VoiceCommandResponse response = understandOnPage("切换到北京", "vision");

        assertThat(response.getIntent()).isEqualTo("unknown");
    }

    @Test
    void fallbackSelectsVisionSceneAndPicker() {
        VoiceCommandResponse response = understandOnPage("我想识别十足路口", "vision");

        assertThat(response.getIntent()).isEqualTo("set_vision_scene");
        assertThat(response.getParams()).containsEntry("scene", "crossroad");
        assertThat(response.getParams()).containsEntry("openPicker", true);
    }

    @Test
    void fallbackUnderstandsFuzzyForwardVisionRequest() {
        VoiceCommandResponse fromHome = understandOnPage("我想看看前面有什么", "home");
        VoiceCommandResponse fromVision = understandOnPage("我想看看前面有什么", "vision");

        assertThat(fromHome.getIntent()).isEqualTo("open_vision");
        assertThat(fromVision.getIntent()).isEqualTo("choose_vision_image");
    }

    @Test
    void fallbackTreatsReadBookAsTextReadingScene() {
        VoiceCommandResponse response = understandOnPage("我想读读书", "vision");

        assertThat(response.getIntent()).isEqualTo("set_vision_scene");
        assertThat(response.getParams()).containsEntry("scene", "text-reading");
        assertThat(response.getParams()).containsEntry("openPicker", true);
    }

    @Test
    void fallbackOpensVisionImagePicker() {
        VoiceCommandResponse response = understandOnPage("打开选图界面", "vision");

        assertThat(response.getIntent()).isEqualTo("choose_vision_image");
    }

    private VoiceCommandResponse understand(String text) {
        return understandOnPage(text, "home");
    }

    private VoiceCommandResponse understandOnPage(String text, String page) {
        VoiceCommandRequest request = new VoiceCommandRequest();
        request.setText(text);
        request.setPage(page);
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
