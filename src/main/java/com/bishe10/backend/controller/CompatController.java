package com.bishe10.backend.controller;

import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CompatController {

    @GetMapping("/login/postCodeVerify")
    public Map<String, Object> postCodeVerify() {
        return ApiResponse.ok(Map.of(
                "message", "验证码正确",
                "token", "demo-access-token"
        ));
    }

    @GetMapping("/dataCenter/member")
    public Map<String, Object> member() {
        return ApiResponse.ok(templatePayload(List.of(
                item("name", "资讯浏览量", "number", "2.1W"),
                item("name", "识图调用量", "number", "836"),
                item("name", "语音播报量", "number", "1.4W")
        )));
    }

    @GetMapping("/dataCenter/interaction")
    public Map<String, Object> interaction() {
        return ApiResponse.ok(templatePayload(List.of(
                item("name", "浏览量", "number", "919"),
                item("name", "点赞量", "number", "227"),
                item("name", "分享量", "number", "104"),
                item("name", "收藏量", "number", "47")
        )));
    }

    @GetMapping("/dataCenter/complete-rate")
    public Map<String, Object> completeRate() {
        return ApiResponse.ok(templatePayload(List.of(
                item("time", "12:00", "percentage", "80"),
                item("time", "14:00", "percentage", "60"),
                item("time", "16:00", "percentage", "85"),
                item("time", "18:00", "percentage", "43"),
                item("time", "20:00", "percentage", "60"),
                item("time", "22:00", "percentage", "95")
        )));
    }

    @GetMapping("/dataCenter/area")
    public Map<String, Object> area() {
        return ApiResponse.ok(templatePayload(List.of(
                areaItem("城市路口播报", "4442", "456", "456"),
                areaItem("超市货架识别", "3821", "402", "511"),
                areaItem("社区资讯推送", "2970", "388", "421")
        )));
    }

    @GetMapping("/api/searchHistory")
    public Map<String, Object> searchHistory() {
        return ApiResponse.ok(Map.of(
                "historyWords", List.of("路口红绿灯", "超市货架", "社区志愿服务", "无障碍电梯")
        ));
    }

    @GetMapping("/api/searchPopular")
    public Map<String, Object> searchPopular() {
        return ApiResponse.ok(Map.of(
                "popularWords", List.of(
                        "路口通行时需要优先播报哪些信息",
                        "视障人士超市购物场景怎么做识别更实用",
                        "语音播报频率怎么控制更不打扰用户",
                        "定位资讯推送如何筛选真正有价值的信息"
                )
        ));
    }

    @GetMapping("/api/genPersonalInfo")
    public Map<String, Object> personalInfo() {
        return ApiResponse.ok(Map.of(
                "image", "/static/avatar1.png",
                "name", "晨星用户",
                "star", "天秤座",
                "gender", 0,
                "birth", "1999-09-27",
                "address", List.of("440000", "440300"),
                "brief", "关注无障碍资讯、情境识别与语音播报",
                "photos", List.of()
        ));
    }

    @GetMapping("/api/getServiceList")
    public Map<String, Object> serviceList() {
        return ApiResponse.ok(Map.of(
                "service", List.of(
                        Map.of("image", "/static/icon_td.png", "name", "数据中心", "type", "data", "url", "/pages/dataCenter/index"),
                        Map.of("image", "/static/icon_td.png", "name", "历史记录", "type", "history", "url", "/pages/my/index"),
                        Map.of("image", "/static/icon_td.png", "name", "资讯推荐", "type", "news", "url", "/pages/home/index"),
                        Map.of("image", "/static/icon_td.png", "name", "拍照识图", "type", "vision", "url", "/pages/message/index")
                )
        ));
    }

    private Map<String, Object> templatePayload(List<Map<String, Object>> list) {
        Map<String, Object> succ = new LinkedHashMap<>();
        succ.put("data", Map.of("list", list));
        succ.put("statusCode", 200);
        succ.put("header", Map.of("content-type", "application/json; charset=utf-8"));

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("succ", succ);

        return Map.of(
                "returnType", "succ",
                "generateType", "template",
                "template", template
        );
    }

    private Map<String, Object> item(String key1, String value1, String key2, String value2) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(key1, value1);
        item.put(key2, value2);
        return item;
    }

    private Map<String, Object> areaItem(String title, String global, String north, String east) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("标题", title);
        item.put("全球", global);
        item.put("华北", north);
        item.put("华东", east);
        return item;
    }
}
