# LLM 配置模板编写指南

> 本文档说明如何为 `rag_extract_split/llmkit` 编写一个 LLM / Embedding 配置模板，并演示如何把一个实际的 `curl` 请求转换为模板 YAML。

---

## 一、模板文件位置

- **内置模板**：`rag_extract_split/llmkit/templates/*.yaml`
- **用户自定义模板**：运行时会持久化到 `data/llmkit/templates/*.yaml`

模板文件名建议与 `name` 字段保持一致，例如 `openai_compatible.yaml`。

---

## 二、模板整体结构

```yaml
name: openai_compatible              # 模板唯一标识（英文，文件名一致）
display_name: OpenAI 兼容接口        # 界面展示名称
description: 支持 OpenAI API 风格... # 简短说明
version: "1.0"                       # 模板版本

ui:
  form:                              # Web/CLI 表单字段定义
    - key: name
      label: 名称
      type: text
      required: true
    - key: connection.base_url
      label: 基础 URL
      type: text
      required: true
    ...

defaults:                            # 基于该模板创建配置时的默认值骨架
  connection:
    base_url: https://api.openai.com/v1
    api_key: YOUR_API_KEY_HERE
    timeout: 600
  request:
    method: POST
    url_suffix: /chat/completions
    headers:
      Content-Type: application/json
      Authorization: Bearer ${connection.api_key}
    data:
      model: gpt-4o
      temperature: 0.7
      max_tokens: 4096
      messages: []
  runtime:
    stream_enabled: false

schema:                              # 字段类型与必填校验规则
  sections:
    connection:
      required: true
      fields:
        base_url:
          type: string
          required: true
        ...
```

---

## 三、核心字段说明

### 3.1 顶层元信息

| 字段 | 说明 |
|------|------|
| `name` | 模板唯一标识，英文、下划线，与文件名一致 |
| `display_name` | 界面上显示的中文名称 |
| `description` | 简短描述，帮助用户理解适用场景 |
| `version` | 模板版本号 |

### 3.2 `ui.form` 表单字段

用于在 Web 界面生成配置表单。常用字段类型：

| type | 说明 |
|------|------|
| `text` | 单行文本 |
| `password` | 密码输入（API Key 等敏感信息） |
| `number` | 浮点数 |
| `int` | 整数 |
| `checkbox` | 布尔开关 |
| `select` | 下拉选择（需配合 `options`） |
| `textarea` | 多行文本 |

`key` 使用点号路径，最终映射到 `defaults` 中的嵌套字段，例如：

- `connection.base_url` → `defaults.connection.base_url`
- `request.data.model` → `defaults.request.data.model`
- `runtime.stream_enabled` → `defaults.runtime.stream_enabled`

常用属性：

| 属性 | 说明 |
|------|------|
| `required` | 是否必填 |
| `default` | 默认值 |
| `placeholder` | 输入框占位提示 |
| `help` | 字段说明 |
| `sensitive` | 是否敏感（日志中脱敏） |
| `min` / `max` / `step` | number 类型限制 |

### 3.3 `defaults` 默认值骨架

这是基于模板生成实际配置时的初始内容。运行时会由 UI 填充用户输入，最终生成一个 Profile。

#### 三个标准顶层节点

```yaml
defaults:
  connection:     # 连接相关：base_url、api_key、timeout、mode 等
  request:        # 请求相关：method、url_suffix、headers、data 等
  runtime:        # 运行时扩展：stream_enabled、自定义解析路径等
```

#### `${...}` 变量引用

在 `defaults.request.headers` 或 `defaults.request.data` 中，可以使用 `${connection.api_key}` 这样的占位符，表示运行时从 `connection` 段读取实际值。

例如：

```yaml
headers:
  Authorization: Bearer ${connection.api_key}
```

实际调用时会被替换为 `Bearer sk-xxxxx`。

### 3.4 `schema` 校验规则

用于校验用户保存的配置是否符合类型要求。支持：

- `type`: string / int / float / bool / list / dict
- `required`: true / false
- `default`: 默认值
- `sensitive`: 是否敏感字段
- 嵌套 `fields`: dict 类型内部字段校验

---

## 四、实战：把 curl 请求转成模板

### 4.1 原始 curl 示例

假设你要接入一个国产兼容 OpenAI 的模型服务，已有如下调用示例：

```bash
curl -X POST "https://api.example.com/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-1234567890abcdef" \
  -d '{
    "model": "qwen2.5-14b-instruct",
    "messages": [
      {"role": "system", "content": "你是一个 helpful assistant。"},
      {"role": "user", "content": "你好"}
    ],
    "temperature": 0.7,
    "max_tokens": 4096,
    "stream": false
  }'
```

### 4.2 提取模板字段

| curl 中的信息 | 模板字段 |
|--------------|---------|
| `https://api.example.com/v1` | `connection.base_url` |
| `Bearer sk-1234567890abcdef` | `connection.api_key`（运行时拼成 `Authorization`） |
| `qwen2.5-14b-instruct` | `request.data.model` |
| `temperature: 0.7` | `request.data.temperature` |
| `max_tokens: 4096` | `request.data.max_tokens` |
| `/chat/completions` | `request.url_suffix` |
| `stream: false` | `runtime.stream_enabled` |

### 4.3 写成模板 YAML

```yaml
name: example_openai_compatible
display_name: Example 国产 OpenAI 兼容接口
description: 适配 https://api.example.com 的 OpenAI 风格 chat completions 接口。
version: "1.0"

ui:
  form:
    - key: name
      label: 配置名称
      type: text
      placeholder: 我的 Qwen2.5
      required: true

    - key: connection.base_url
      label: 基础 URL
      type: text
      placeholder: https://api.example.com/v1
      default: https://api.example.com/v1
      required: true
      help: 服务基础地址，不要以斜杠结尾。

    - key: connection.api_key
      label: API Key
      type: password
      placeholder: sk-xxxxxxxxxxxxxxxxxxxxxxxx
      required: true
      sensitive: true
      help: 从服务商控制台获取的 API Key。

    - key: connection.timeout
      label: 超时时间（秒）
      type: int
      default: 600
      required: true

    - key: request.data.model
      label: 模型名称
      type: text
      placeholder: qwen2.5-14b-instruct
      default: qwen2.5-14b-instruct
      required: true

    - key: request.data.temperature
      label: 温度
      type: number
      min: 0
      max: 2
      step: 0.1
      default: 0.7

    - key: request.data.max_tokens
      label: 最大 Token 数
      type: int
      default: 4096
      required: true

    - key: runtime.stream_enabled
      label: 默认流式输出
      type: checkbox
      default: false
      help: 是否默认以 stream=true 调用接口。

defaults:
  connection:
    base_url: https://api.example.com/v1
    api_key: YOUR_API_KEY_HERE
    timeout: 600
  request:
    method: POST
    url_suffix: /chat/completions
    headers:
      Content-Type: application/json
      Authorization: Bearer ${connection.api_key}
    data:
      model: qwen2.5-14b-instruct
      temperature: 0.7
      max_tokens: 4096
      messages: []
      stream: false
  runtime:
    stream_enabled: false

schema:
  sections:
    connection:
      required: true
      fields:
        base_url:
          type: string
          required: true
        api_key:
          type: string
          required: true
          sensitive: true
        timeout:
          type: int
          default: 600
    request:
      required: true
      fields:
        method:
          type: string
          default: POST
        url_suffix:
          type: string
          required: true
        headers:
          type: dict
          default: {}
          fields:
            Content-Type:
              type: string
              default: application/json
            Authorization:
              type: string
              required: true
        data:
          type: dict
          required: true
          fields:
            model:
              type: string
              required: true
            messages:
              type: list
              required: true
            temperature:
              type: float
              default: 0.7
            max_tokens:
              type: int
              required: true
            stream:
              type: bool
              default: false
    runtime:
      required: false
      fields:
        stream_enabled:
          type: bool
          default: false
```

---

## 五、非 OpenAI 风格接口的处理建议

如果接口不是标准 OpenAI 风格，通常需要额外处理：

### 5.1 鉴权方式不同

例如使用 `api-key` header：

```yaml
request:
  headers:
    Content-Type: application/json
    api-key: ${connection.api_key}
```

### 5.2 URL 路径不同

```yaml
request:
  url_suffix: /api/v1/generate
```

### 5.3 请求体字段不同

例如某些接口用 `prompt` 而不是 `messages`：

```yaml
request:
  data:
    prompt: ""
    max_length: 4096
    temperature: 0.7
```

这种情况下，需要在调用层（如 `rag_extract_split/llmkit/caller.py`）做额外的请求体转换。模板本身只负责描述字段结构。

### 5.4 响应字段路径不同

如果响应不是标准 OpenAI 格式，可以在 `runtime` 中增加自定义解析路径，例如：

```yaml
runtime:
  stream_enabled: false
  http_post_content_path: "output.text"        # 文本字段路径
  http_post_usage_path: "usage"                # usage 字段路径
```

并在调用代码中读取这些路径进行解析。

---

## 六、验证模板

保存 YAML 后，可以通过以下 Python 代码快速验证是否能被正确加载：

```python
from rag_extract_split.llmkit import TemplateManager

tm = TemplateManager("rag_extract_split/llmkit/templates", "data/llmkit/templates")
print("模板列表:", tm.list_templates())

tmpl = tm.get_template("example_openai_compatible")
print("模板名称:", tmpl.name)
print("默认值骨架:", tmpl.generate_scaffold("test"))
```

如果 YAML 格式或字段类型有问题，会在这里抛出异常。

---

## 七、注意事项

1. `name` 字段必须全局唯一，不能与内置模板重名。
2. 敏感字段（如 `api_key`）务必标记 `sensitive: true`。
3. `defaults` 中的 `messages: []` 是占位，实际调用时会由代码填充。
4. 模板修改后需要调用 `TemplateManager.reload()` 或重启服务才能生效。
5. 用户通过 Web 界面“新增自定义模板”粘贴的 YAML，保存时会校验 `name` 是否存在且模板可被 `Template.from_dict()` 解析。
