"""GEPA (Genetic-Pareto) reflective prompt evolution.

Implements the open-source GEPA algorithm (arXiv:2507.19457):
- Per-instance Pareto frontier candidate tracking
- LLM-driven reflective mutation from execution traces
- Crossover merge of complementary candidates
- Strict-improvement acceptance criterion
"""

from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer
from evo_agent.optimizer.gepa.gepa_adapter import GEPAAdapter
from evo_agent.optimizer.gepa.pareto_frontier import ParetoFrontier
from evo_agent.optimizer.gepa.reflection_engine import ReflectionEngine

__all__ = [
    "GepaOptimizer",
    "GEPAAdapter",
    "ParetoFrontier",
    "ReflectionEngine",
]
