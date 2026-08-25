#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Backward-compatible single-mode CLI entry point shim.

The actual implementation lives in ``rag_extract_split.cli.single``.
This module exists so that the legacy invocation ``python -m rag_extract_split.single_mode``
continues to work after the package refactoring.
"""

from rag_extract_split.cli.single import main

if __name__ == "__main__":
    raise SystemExit(main())
