from __future__ import annotations

import io
import json
import sys

import pytest

from agent_runner import protocol as p


def test_emit_writes_ndjson(monkeypatch: pytest.MonkeyPatch) -> None:
    buf = io.StringIO()
    monkeypatch.setattr(sys, "stdout", buf)
    p.emit_dc(p.Final(status="success", summary="ok"))
    line = buf.getvalue()
    assert line.endswith("\n")
    obj = json.loads(line)
    assert obj == {"status": "success", "summary": "ok", "artifacts": {}, "type": "final"}


def test_emit_skips_none_fields(monkeypatch: pytest.MonkeyPatch) -> None:
    buf = io.StringIO()
    monkeypatch.setattr(sys, "stdout", buf)
    p.emit_dc(p.AssistantMessage(text="hi"))  # usage is None
    obj = json.loads(buf.getvalue())
    assert "usage" not in obj
    assert obj["text"] == "hi"
    assert obj["type"] == "assistant_message"
