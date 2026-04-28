package com.bishe10.backend.support;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("message", "success");
        response.put("data", data);
        return response;
    }

    public static Map<String, Object> fail(int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("success", false);
        response.put("message", message);
        response.put("data", Map.of());
        return response;
    }
}
