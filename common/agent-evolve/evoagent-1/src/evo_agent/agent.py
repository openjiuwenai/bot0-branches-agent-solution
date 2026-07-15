"""EvoAgent 构建 + skill 注册。"""

from __future__ import annotations

from pathlib import Path

from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

from evo_agent.config import EvolveConfig


async def create_evo_agent(config: EvolveConfig | None = None) -> ReActAgent:
    """构建并返回一个配置好的自进化 Agent。"""
    config = config or EvolveConfig.get()
    agent = ReActAgent(
        card=AgentCard(name="evo_agent"),
        config=ReActAgentConfig(
            model_name=config.target_model,
            api_key=config.llm_api_key,
            api_base=config.llm_base_url,
        ),
    )
    skills_root = Path(__file__).parent.parent.parent / "skills"
    await agent.register_skill(skills_root / "optimize_skill")
    # 未来可扩展:
    # await agent.register_skill(skills_root / "evaluate_skill")
    # await agent.register_skill(skills_root / "create_skill")
    return agent
