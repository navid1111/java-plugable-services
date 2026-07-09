"""FastAPI routes: create an app, drive agent turns, stream events, serve the app."""

import asyncio
import json

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel

from . import workspace
from .chat_store import list_events
from .events import bus
from .sessions import manager
from .ui import INDEX_HTML

router = APIRouter()


class CreateApp(BaseModel):
    prompt: str = ""


class Message(BaseModel):
    text: str


@router.get("/", response_class=HTMLResponse)
async def home() -> str:
    return INDEX_HTML


@router.post("/api/apps", status_code=201)
async def create_app(body: CreateApp) -> dict:
    slug = workspace.new_slug(body.prompt or "app")
    await workspace.scaffold(slug)
    return {"slug": slug}


@router.post("/api/apps/{slug}/message", status_code=202)
async def message(slug: str, body: Message) -> dict:
    if not workspace.workspace_dir(slug).exists():
        raise HTTPException(404, "unknown app")
    if manager.is_busy(slug):
        raise HTTPException(409, "app is busy with another turn")
    asyncio.create_task(manager.run_turn(slug, body.text))
    return {"status": "started"}


@router.post("/api/apps/{slug}/interrupt")
async def interrupt(slug: str) -> dict:
    return {"interrupted": await manager.interrupt(slug)}


@router.get("/api/apps/{slug}/files")
async def files(slug: str) -> JSONResponse:
    cwd = workspace.workspace_dir(slug)
    if not cwd.exists():
        raise HTTPException(404, "unknown app")
    return JSONResponse(workspace.list_files(cwd))


@router.get("/api/apps/{slug}/history")
async def history(slug: str) -> dict:
    cwd = workspace.workspace_dir(slug)
    if not cwd.exists():
        raise HTTPException(404, "unknown app")
    return {"events": await list_events(cwd)}


@router.get("/api/apps/{slug}/events")
async def events(slug: str, request: Request) -> StreamingResponse:
    queue = bus.subscribe(slug)

    async def stream():
        try:
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event_type, data = await asyncio.wait_for(queue.get(), timeout=15.0)
                    yield f"event: {event_type}\ndata: {json.dumps(data)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keep-alive\n\n"
        finally:
            bus.unsubscribe(slug, queue)

    return StreamingResponse(stream(), media_type="text/event-stream")


# --- serve the generated static app -----------------------------------------

@router.get("/apps/{slug}/")
async def serve_index(slug: str):
    cwd = workspace.workspace_dir(slug)
    entry = workspace.safe_file(cwd, "index.html")
    if entry is None:
        raise HTTPException(404, "app not built yet")
    return FileResponse(entry)


@router.get("/apps/{slug}/{path:path}")
async def serve_file(slug: str, path: str):
    cwd = workspace.workspace_dir(slug)
    target = workspace.safe_file(cwd, path or "index.html")
    if target is None:
        raise HTTPException(404, "not found")
    return FileResponse(target)
