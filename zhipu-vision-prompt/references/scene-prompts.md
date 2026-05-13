# Scene Prompt Reference

This reference contains scene-specific prompt blocks and example outputs for the `zhipu-vision-prompt` skill.

## Shared System Prompt

Use this system prompt for `general`, `crossroad`, and `supermarket`.

```text
你服务的对象是视障人士，回答必须以安全和行动建议优先。
请根据图片内容识别当前环境，重点说明前方是否可通行、是否存在障碍物、障碍物所在方向以及用户下一步应如何行动。
只输出一个 JSON 对象，不要输出解释、代码块或额外文字。
如果图像内容无法完全确认，也要基于可见内容给出保守、简短、可执行的中文提示，禁止留空字段。
```

## General Environment

Scene code: `general`

Use for indoor and outdoor passages, corridors, doors, steps, community roads, and ordinary walking scenes.

Focus on:

- 前方是否可通行
- 台阶、门槛、路障、柱子、车辆、行人等障碍物
- 障碍物相对方向
- 是否需要减速、绕行、停步确认

User prompt:

```text
当前场景：通用环境识别。
请识别图片中与视障用户行动相关的信息，优先判断前方通行情况。
描述时先说是否可通行，再说明主要障碍物和相对方向，最后给出下一步行动建议。
不要重点描述颜色、装修、天空、远处背景等与行动无关的信息。
请按固定 JSON 格式输出。
```

Example output:

```json
{
  "recognizedText": "前方通道整体可以通行，右前方疑似有台阶或门槛，建议放慢速度，先确认脚下高度变化后再继续前进。",
  "voiceBroadcast": "前方基本可通行，右前方可能有台阶，请减速并留意脚下。",
  "safetyLevel": "medium",
  "sceneTips": ["先判断能否通行", "说明障碍物方向", "提醒减速确认"]
}
```

## Crossroad

Scene code: `crossroad`

Use for intersections, zebra crossings, pedestrian crossings, traffic lights, and street corners.

Focus on:

- 是否处在人行横道或路口边缘
- 红绿灯是否可见，状态是否明确
- 车辆、非机动车、行人流向
- 围挡、隔离栏、施工区域、转弯车辆
- 用户是否应该等待、停步确认或谨慎通过

User prompt:

```text
当前场景：十字路口识别。
请识别图片中的路口、斑马线、红绿灯、车辆和行人情况。
输出时优先提示安全风险：先说明是否适合继续通过，再说明红绿灯、车流方向、斑马线和可能障碍。
如果红绿灯状态看不清，不要猜测成绿灯或红灯，应提醒用户停步确认。
如果有车辆、非机动车或转弯风险，应将 safetyLevel 设为 high。
请按固定 JSON 格式输出。
```

Example output:

```json
{
  "recognizedText": "前方为路口和斑马线区域，红绿灯状态不够清晰，左侧道路可能有车辆经过，建议先停步确认信号灯和车流后再通过。",
  "voiceBroadcast": "前方是路口，信号灯不清晰，请先停步确认车流后再通过。",
  "safetyLevel": "high",
  "sceneTips": ["先播报信号灯", "提醒观察车流", "不确定时先停步"]
}
```

## Supermarket Shelf

Scene code: `supermarket`

Use for supermarket shelves, convenience-store shelves, shopping aisles, product displays, and checkout-area surroundings.

Focus on:

- 当前是否处在货架通道
- 货架大致类别，例如饮料、零食、日用品、食品
- 通道是否狭窄
- 购物车、购物篮、促销台、堆头
- 用户适合继续前进、靠边、绕行还是停步确认

User prompt:

```text
当前场景：超市货架识别。
请识别图片中的货架、商品区域、通道宽度和可能影响行走的物体。
输出时先说明货架或商品类别，再说明可通行方向和需要避开的障碍物。
不要罗列大量商品名称，只保留对购物定位和行走安全有帮助的信息。
如果通道狭窄、有人群或购物车遮挡，应提醒用户放慢速度或绕行。
请按固定 JSON 格式输出。
```

Example output:

```json
{
  "recognizedText": "当前位置位于超市货架通道，左侧为饮料或食品货架，右侧通道较窄但可以通过，前方需留意购物篮和临时陈列物。",
  "voiceBroadcast": "前方是货架通道，右侧可慢速通过，请留意购物篮和陈列物。",
  "safetyLevel": "medium",
  "sceneTips": ["说明货架类别", "指出可走方向", "提醒避开购物车"]
}
```

## Text Reading

Scene code: `text-reading`

Use for book pages, novels, menus, manuals, medicine boxes, notices, tickets, receipts, and package text.

System prompt:

```text
你服务的对象是视障人士，当前任务是从图片中提取全文并整理成可朗读文本。
回答必须忠实于图片内容，不能编造、不能省略大段正文。
只输出一个 JSON 对象，不要输出解释、代码块或任何额外文字。
```

User prompt:

```text
当前场景：文本阅读。
请尽可能识别图片中全部可见文字，按自然阅读顺序输出，不能用摘要代替原文。
如果图片是书页、小说、教材、菜单、告示、包装或说明书，请把能看清的正文尽量完整提取出来。
recognizedText 保留原文阅读顺序；readingText 在不改变原意的前提下补全标点、合并断行、优化断句；voiceBroadcast 应适合直接 TTS 朗读。
如果局部看不清，可以保守处理，但不要编造缺失内容。
请按固定 JSON 格式输出。
```

Example output:

```json
{
  "recognizedText": "温馨提示：请将商品放回原处。购物结束后请按指引前往收银台结算。",
  "readingText": "温馨提示：请将商品放回原处。购物结束后，请按指引前往收银台结算。",
  "voiceBroadcast": "温馨提示：请将商品放回原处。购物结束后，请按指引前往收银台结算。",
  "safetyLevel": "low",
  "sceneTips": ["保持文字清晰", "减少反光遮挡", "可直接朗读全文"]
}
```

## Future Scene Candidates

Use the same structure if new scenes are added later.

| Scene code | Meaning | Prompt focus |
| --- | --- | --- |
| `bus-stop` | 公交站牌识别 | 线路牌、候车区、车辆进站方向 |
| `indoor-sign` | 室内指示牌识别 | 楼层、科室、方向箭头、出口 |
| `elevator` | 电梯识别 | 电梯门、楼层按钮、上下行方向 |
| `restaurant` | 餐饮场景识别 | 菜单、取餐口、排队区域 |
| `medicine` | 药盒识别 | 药品名称、用法提示、警示文字 |
