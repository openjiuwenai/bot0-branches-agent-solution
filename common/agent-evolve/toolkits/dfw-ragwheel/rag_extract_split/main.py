#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Backward-compatible CLI entry point shim.

The actual implementation lives in ``rag_extract_split.cli.main``.
This module exists so that the legacy invocation ``python -m rag_extract_split.main``
continues to work after the package refactoring.
"""

from rag_extract_split.cli.main import main

if __name__ == "__main__":
    raise SystemExit(main())
