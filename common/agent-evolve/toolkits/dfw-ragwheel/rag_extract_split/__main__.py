#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Package-level CLI entry point.

Usage:
    python -m rag_extract_split [SUBCOMMAND] [OPTIONS]
"""

from rag_extract_split.cli.main import main

if __name__ == "__main__":
    raise SystemExit(main())
