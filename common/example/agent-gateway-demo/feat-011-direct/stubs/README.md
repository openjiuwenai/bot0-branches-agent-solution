# Downstream stubs（Fake RDC + Fake Runtime）

替代联调里 **国庆 RDC** 与 **下游 Agent Runtime**，供 Gateway example 自测。

```bash
python3 downstream_stub.py --rdc-port 18092 --runtime-port 18094
```

Gateway 启动时：

```text
--gateway.rdc.base-url=http://127.0.0.1:18092
--gateway.default-agent-id=travel-hotel
--gateway.test-credential.token=mock-token
--gateway.test-credential.principalId=tester
--gateway.test-credential.tenantId=tenant-1
```

已知 agent：`scripted-verify`、`travel-hotel`、`default-agent-1` → 解析到 stub runtime。  
未知 agent → 空候选（触发 `ROUTE_NO_CANDIDATES`）。
