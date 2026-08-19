from __future__ import annotations

import json
from typing import List, Protocol

import requests


class Embedder(Protocol):
    def embed(self, texts: List[str]) -> List[List[float]]: ...

    def embed_one(self, text: str) -> List[float]: ...


class OpenAIEmbedder:
    """OpenAI 兼容 Embedding（按需导入 openai）。"""

    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        batch_size: int = 32,
        timeout: float = 120.0,
    ):
        if not api_key:
            raise ValueError("未配置 EMBEDDING_API_KEY，请在 .env 中设置")
        from openai import OpenAI

        self._client = OpenAI(
            api_key=api_key, base_url=base_url, timeout=timeout, max_retries=2
        )
        self._model = model
        self._batch_size = batch_size

    def embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        vectors: List[List[float]] = []
        for i in range(0, len(texts), self._batch_size):
            batch = texts[i : i + self._batch_size]
            resp = self._client.embeddings.create(model=self._model, input=batch)
            sorted_data = sorted(resp.data, key=lambda x: x.index)
            vectors.extend([item.embedding for item in sorted_data])
        return vectors

    def embed_one(self, text: str) -> List[float]:
        return self.embed([text])[0]


class HttpEmbedder:
    """自定义 HTTP Embedding 服务。"""

    def __init__(
        self,
        url: str,
        dim: int = 1536,
        batch_size: int = 32,
        timeout: float = 120.0,
    ):
        if not url:
            raise ValueError("未配置 HTTP_EMBEDDING_URL，请在 .env 中设置")
        self._url = url
        self._dim = dim
        self._batch_size = batch_size
        self._timeout = timeout

    def embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        vectors: List[List[float]] = []
        for i in range(0, len(texts), self._batch_size):
            vectors.extend(self._embed_batch(texts[i : i + self._batch_size]))
        return vectors

    def embed_one(self, text: str) -> List[float]:
        return self.embed([text])[0]

    def _embed_batch(self, texts: List[str]) -> List[List[float]]:
        headers = {
            "Content-Type": "application/json",
            "User-Agent": "DataQualCheck/1.0",
        }
        data = {"query": texts}
        try:
            response = requests.post(
                self._url,
                headers=headers,
                data=json.dumps(data),
                timeout=self._timeout,
            )
            response.raise_for_status()
            result = response.json()
            if "embedding" in result:
                return list(result["embedding"])
        except Exception as e:
            raise RuntimeError(f"Embedding HTTP 请求失败: {e}") from e
        return [[0.0] * self._dim for _ in texts]


class LocalEmbedder:
    """本地 Sentence-Transformers Embedding 模型（无需 API Key）。"""

    _cache: dict = {}

    def __init__(self, model_name: str, batch_size: int = 32, **_kwargs):
        if not model_name:
            raise ValueError("未配置 EMBEDDING_MODEL，请在 .env 中设置")
        self._model_name = model_name
        self._batch_size = batch_size
        self._model = None

    def _load_model(self):
        if self._model is None:
            key = self._model_name
            if key not in LocalEmbedder._cache:
                from sentence_transformers import SentenceTransformer

                LocalEmbedder._cache[key] = SentenceTransformer(key)
            self._model = LocalEmbedder._cache[key]
        return self._model

    def embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        model = self._load_model()
        vectors: List[List[float]] = []
        for i in range(0, len(texts), self._batch_size):
            batch = texts[i : i + self._batch_size]
            embeddings = model.encode(batch)
            # 确保返回原生 Python float，避免 numpy.float32 被 Chroma 拒绝
            vectors.extend(embeddings.astype(float).tolist())
        return vectors

    def embed_one(self, text: str) -> List[float]:
        return self.embed([text])[0]


class FallbackEmbedder:
    """Offline fallback embedder based on character n-gram hashing.

    No model download required. Used for quick code verification and demos.
    For production, use 'local' or 'openai' embedding mode.
    """

    def __init__(self, dim: int = 384, ngram: int = 2, **_kwargs):
        self._dim = dim
        self._ngram = ngram

    def embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        return [self._hash_vector(t) for t in texts]

    def embed_one(self, text: str) -> List[float]:
        return self._hash_vector(text)

    def _hash_vector(self, text: str) -> List[float]:
        vec = [0.0] * self._dim
        s = (text or "").strip()
        if not s:
            return vec
        n = self._ngram
        for i in range(len(s) - n + 1):
            gram = s[i : i + n]
            h = hash(gram) % self._dim
            vec[h] += 1.0
        norm = sum(v * v for v in vec) ** 0.5
        if norm > 0:
            vec = [v / norm for v in vec]
        return vec


def create_embedder(settings: dict) -> Embedder:
    mode = (settings.get("embedding_mode") or "openai").lower()
    timeout = float(settings.get("api_timeout", 120.0))
    batch_size = int(settings.get("embedding_batch_size", 32))

    if mode == "http":
        return HttpEmbedder(
            url=settings.get("http_embedding_url", ""),
            dim=int(settings.get("http_embedding_dim", 1536)),
            batch_size=batch_size,
            timeout=timeout,
        )

    if mode == "local":
        return LocalEmbedder(
            model_name=settings.get("embedding_model", "BAAI/bge-small-zh"),
            batch_size=batch_size,
        )

    if mode == "fallback":
        return FallbackEmbedder(
            dim=int(settings.get("fallback_embedding_dim", 384)),
        )

    return OpenAIEmbedder(
        api_key=settings.get("embedding_api_key", ""),
        base_url=settings.get("embedding_base_url", ""),
        model=settings.get("embedding_model", ""),
        batch_size=batch_size,
        timeout=timeout,
    )


# 兼容旧代码引用
OnlineEmbedder = OpenAIEmbedder
