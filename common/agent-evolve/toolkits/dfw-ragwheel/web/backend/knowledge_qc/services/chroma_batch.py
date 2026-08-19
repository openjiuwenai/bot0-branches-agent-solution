from __future__ import annotations

from backend.knowledge_qc.services.vector_store import ChromaVectorStore


def chroma_upsert_batch_size(settings: dict) -> int:
    rules = settings.get("rules") or {}
    chroma = rules.get("chroma") or {}
    return max(
        1,
        int(
            chroma.get(
                "upsert_batch_size", settings.get("chroma_upsert_batch_size", 100)
            )
        ),
    )


def create_chroma_store(settings: dict) -> ChromaVectorStore:
    c = settings.get("rules", {}).get("chroma", {})
    return ChromaVectorStore(
        persist_dir=settings["chroma_persist_dir"],
        production_name=c.get("production_collection", "kb_production"),
        staging_name=c.get("staging_collection", "kb_staging"),
        intent_production_name=c.get(
            "intent_production_collection", "kb_intent_production"
        ),
        intent_staging_name=c.get(
            "intent_staging_collection", "kb_intent_staging"
        ),
        init_timeout=settings.get("chroma_init_timeout", 15.0),
        upsert_batch_size=chroma_upsert_batch_size(settings),
    )
