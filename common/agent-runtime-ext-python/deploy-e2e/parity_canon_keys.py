import json
import sys


#: 规范化只动一处：`custom_rsp_data.data` 的键序。
#: 存量那一层的键序**每次运行都不同**——`dict_to_a2a` 走 protobuf `Struct`
#: （`applications/a2a_service/channels/dict_to_a2a.py` 的 `Struct()`），map 字段往返不保序。
#: 三轮实测三种顺序。键序不是契约（JSON 对象无序，且存量自己都不稳定），
#: 但**键集、值、其余全部字符仍逐字节比**。
#:
#: 自检：对 data 为空或单键的帧，本规范化必须是**恒等变换**。不是则说明
#: 这段 JSON 往返顺带改了序列化风格（分隔符、转义），那是在掩盖真差异——
#: 此时退 3，调用方判失败，绝不静默继续。
def canonicalize(text: str) -> str:
    """把每行 SSE 文本的 `custom_rsp_data.data` 键序排好，其余原样返回。

    对 data 为空或单键的帧，本函数必须是**恒等变换**——不是则抛 `ValueError`：
    那说明这段 JSON 往返顺带改了序列化风格（分隔符、转义），是在掩盖真差异。
    """
    out = []
    for line in text.split("\n"):
        if not line.startswith("data: "):
            out.append(line)
            continue
        body = line[6:]
        try:
            envelope = json.loads(body)
        except ValueError:
            out.append(line)
            continue
        inner = envelope.get("custom_rsp_data")
        data = inner.get("data") if isinstance(inner, dict) else None
        if isinstance(data, dict):
            inner["data"] = {k: data[k] for k in sorted(data)}
        canon = json.dumps(envelope, ensure_ascii=False, separators=(", ", ": "))
        if isinstance(data, dict) and len(data) <= 1 and canon != body:
            raise ValueError("键序规范化对空/单键 data 的帧不是恒等变换")
        out.append("data: " + canon)
    return "\n".join(out)


def main() -> int:
    """读标准输入、写标准输出；恒等性自检不成立时退 3，调用方判失败。"""
    try:
        sys.stdout.write(canonicalize(sys.stdin.read()))
    except ValueError:
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
