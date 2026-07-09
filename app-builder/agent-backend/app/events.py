"""Tiny in-memory pub/sub so SSE subscribers receive agent events per app."""

import asyncio
from collections import defaultdict


class EventBus:
    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = defaultdict(set)

    def subscribe(self, app_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue()
        self._subscribers[app_id].add(queue)
        return queue

    def unsubscribe(self, app_id: str, queue: asyncio.Queue) -> None:
        self._subscribers[app_id].discard(queue)

    async def publish(self, app_id: str, event_type: str, data: dict) -> None:
        for queue in list(self._subscribers.get(app_id, ())):
            await queue.put((event_type, data))


bus = EventBus()
