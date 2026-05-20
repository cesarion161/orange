"""Entrypoint: `python -m agent_runner` or the `agent-runner` console script."""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys

from . import protocol as p
from .runner import Runner


def run() -> None:
    level_name = os.environ.get("AGENT_RUNNER_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    logging.basicConfig(
        level=level,
        stream=sys.stderr,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    runner = Runner()

    def _term(*_):
        runner._cancel_event.set()

    signal.signal(signal.SIGTERM, _term)
    signal.signal(signal.SIGINT, _term)

    try:
        rc = asyncio.run(runner.run())
    except Exception as exc:
        p.emit_dc(p.Error(message=str(exc), code="unhandled"))
        rc = 1
    sys.exit(rc)


if __name__ == "__main__":
    run()
