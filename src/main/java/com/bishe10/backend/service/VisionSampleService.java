package com.bishe10.backend.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class VisionSampleService {

    private static final List<SampleAsset> SAMPLES = List.of(
            new SampleAsset(
                    "crosswalk-demo",
                    "十字路口参考图",
                    "crossroad",
                    "sample-images/crosswalk-demo.jpg",
                    "image/jpeg",
                    "适合识别路口、斑马线与风险播报。",
                    "https://commons.wikimedia.org/wiki/File%3ACrosswalk_striping_style_for_signalized_crossings_%2818257330349%29.jpg"
            ),
            new SampleAsset(
                    "supermarket-demo",
                    "超市货架参考图",
                    "supermarket",
                    "sample-images/supermarket-demo.jpg",
                    "image/jpeg",
                    "适合识别货架通道、购物车和绕行提示。",
                    "https://commons.wikimedia.org/wiki/File%3AGrocery_Store_Aisle%2C_vermont.jpg"
            )
    );

    public List<Map<String, Object>> listSamples() {
        return SAMPLES.stream().map(sample -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("key", sample.key());
            payload.put("title", sample.title());
            payload.put("scene", sample.scene());
            payload.put("hint", sample.hint());
            payload.put("imageUrl", "/api/vision/samples/" + sample.key() + "/image");
            payload.put("sourceUrl", sample.sourceUrl());
            return payload;
        }).toList();
    }

    public Optional<SampleAsset> find(String sampleKey) {
        if (sampleKey == null || sampleKey.isBlank()) {
            return Optional.empty();
        }
        return SAMPLES.stream()
                .filter(sample -> sample.key().equals(sampleKey.trim()))
                .findFirst();
    }

    public Optional<SamplePayload> loadForAnalysis(String sampleKey) {
        return find(sampleKey).flatMap(sample -> {
            try {
                Resource resource = new ClassPathResource(sample.classpathLocation());
                if (!resource.exists()) {
                    return Optional.empty();
                }
                return Optional.of(new SamplePayload(
                        sample,
                        resource.getContentAsByteArray(),
                        sample.mimeType()
                ));
            } catch (IOException error) {
                return Optional.empty();
            }
        });
    }

    public Optional<Resource> loadImage(String sampleKey) {
        return find(sampleKey)
                .map(sample -> (Resource) new ClassPathResource(sample.classpathLocation()))
                .filter(Resource::exists);
    }

    public record SampleAsset(
            String key,
            String title,
            String scene,
            String classpathLocation,
            String mimeType,
            String hint,
            String sourceUrl
    ) {
    }

    public record SamplePayload(
            SampleAsset sample,
            byte[] bytes,
            String mimeType
    ) {
    }
}
