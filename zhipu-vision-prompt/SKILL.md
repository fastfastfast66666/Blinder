---
name: zhipu-vision-prompt
description: Use this skill when designing, reviewing, or revising prompts for Zhipu GLM vision API in an assistive mini-program for visually impaired users, especially for general environment recognition, crossroad safety, supermarket shelf navigation, and text-reading OCR scenes.
---

# Zhipu Vision Prompt

This skill guides prompt writing for the project's vision-recognition module. It is prompt-engineering only. Do not change backend or frontend code unless the user explicitly asks for implementation changes.

## When To Use

Use this skill when the task involves:

- Writing prompts for Zhipu / GLM vision API.
- Improving image-recognition output for visually impaired users.
- Designing scene-specific prompts for `general`, `crossroad`, `supermarket`, or `text-reading`.
- Keeping model output compatible with the mini-program's JSON result format.

## Workflow

1. Identify the scene code from the user request or UI state.
2. Select the matching prompt pattern.
3. Keep safety and action guidance first for non-text scenes.
4. Keep faithful text extraction first for `text-reading`.
5. Require JSON-only output.
6. Check every returned field is non-empty and usable for speech playback.

For detailed scene prompts, read [references/scene-prompts.md](references/scene-prompts.md).

## Scene Codes

| Scene code | Meaning | Main goal |
| --- | --- | --- |
| `general` | 通用环境识别 | 判断前方是否可通行，并提示障碍物方向 |
| `crossroad` | 十字路口识别 | 优先提示红绿灯、车流、斑马线和停步确认 |
| `supermarket` | 超市货架识别 | 说明货架类别、通道方向和购物障碍 |
| `text-reading` | 文本阅读 | 忠实提取图片文字，并整理为可朗读文本 |

## Output Contract

For non-text scenes, require this JSON object:

```json
{
  "recognizedText": "40到90字的中文短描述，必须非空，优先说明前方是否可通行、障碍物和方向",
  "voiceBroadcast": "25到60字的中文播报短句，必须非空，可直接朗读",
  "safetyLevel": "low、medium 或 high",
  "sceneTips": ["每项不超过18字", "总数固定3项", "强调行动建议"]
}
```

For text-reading scenes, require this JSON object:

```json
{
  "recognizedText": "按阅读顺序提取出的全文，尽量保留自然段和换行，不能为空",
  "readingText": "在忠实原文的前提下整理后的朗读文本，可补全标点和段落，不能为空",
  "voiceBroadcast": "可直接用于 TTS 朗读的文本，通常与 readingText 接近，不能为空",
  "safetyLevel": "low",
  "sceneTips": ["固定3条", "每条不超过18字", "强调拍摄和朗读建议"]
}
```

## Prompt Rules

- Always write for visually impaired users.
- Prefer short, direct Chinese sentences.
- For safety scenes, say the action first: stop, slow down, confirm, pass, or detour.
- Mention relative direction when possible: 正前方、左前方、右侧、脚下.
- Do not describe decorative details unless they affect movement.
- Do not invent signal-light status, text content, product labels, or road conditions.
- If the image is unclear, give conservative guidance instead of guessing.
- Output JSON only. No Markdown, no code fences, no explanation text in the API response.

## Risk Level Rules

Use `low` for text reading or clearly safe static scenes.

Use `medium` when the user can continue but should slow down, such as nearby steps, shelves, carts, people, narrow aisles, or uncertain floor changes.

Use `high` when the scene includes road crossings, unclear traffic lights, vehicles, construction barriers, or any situation where the user should stop and confirm before moving.

## Good Broadcast Style

Prefer:

- 前方基本可通行，右前方可能有台阶，请减速。
- 前方是路口，信号灯不清晰，请先停步确认车流。
- 左侧是货架，右侧通道较窄，可以慢慢通过。

Avoid:

- 画面呈现出一个复杂的城市交通空间。
- 环境整体较为丰富，包含若干物体。
- 根据图片推测当前可能具备一定风险。
