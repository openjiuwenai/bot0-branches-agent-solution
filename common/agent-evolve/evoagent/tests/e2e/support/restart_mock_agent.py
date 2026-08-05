"""Trigger the fake Agent's asynchronous logical restart."""

from __future__ import annotations

import sys
import urllib.request


def main() -> None:
    request = urllib.request.Request(sys.argv[1], data=b"", method="POST")
    with urllib.request.urlopen(request, timeout=2) as response:  # noqa: S310
        if response.status != 200:
            raise RuntimeError(f"restart endpoint returned HTTP {response.status}")


if __name__ == "__main__":
    main()
