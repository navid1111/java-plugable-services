"""Persistent per-app chat transcript storage.

The SSE bus is intentionally in-memory, but chat history should survive page
reloads and backend restarts. Each workspace owns two small JSON files:

* .appbuilder/chat_events.json stores the UI transcript.
* .appbuilder/agent_history.json stores the embedded Hermes conversation state.
"""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Any

_META_DIR = ".appbuilder"
_EVENTS_FILE = "chat_events.json"
_HISTORY_FILE = "agent_history.json"

_io_lock = asyncio.Lock()


def _meta_dir(cwd: Path) -> Path:
    path = cwd / _META_DIR
    path.mkdir(parents=True, exist_ok=True)
    return path


def _read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        return default
    except json.JSONDecodeError:
        return default


def _write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    tmp.replace(path)


async def append_event(cwd: Path, event_type: str, data: dict[str, Any]) -> dict[str, Any]:
    """Append and return a transcript event for replay to the browser."""
    async with _io_lock:
        path = _meta_dir(cwd) / _EVENTS_FILE
        events = _read_json(path, [])
        event = {"type": event_type, "data": data, "createdAt": time.time()}
        events.append(event)
        _write_json(path, events)
        return event


async def list_events(cwd: Path) -> list[dict[str, Any]]:
    async with _io_lock:
        events = _read_json(_meta_dir(cwd) / _EVENTS_FILE, [])
        return events if isinstance(events, list) else []


async def load_agent_history(cwd: Path) -> list[dict[str, Any]]:
    async with _io_lock:
        history = _read_json(_meta_dir(cwd) / _HISTORY_FILE, [])
        return history if isinstance(history, list) else []


async def save_agent_history(cwd: Path, history: list[dict[str, Any]]) -> None:
    async with _io_lock:
        _write_json(_meta_dir(cwd) / _HISTORY_FILE, history)
