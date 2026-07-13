"""FastAPI routes: create an app, drive agent turns, stream events, serve the app."""

import asyncio
import html
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


class ArchitectureUpdate(BaseModel):
    source: str = ""
    graph: dict | None = None


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


@router.get("/api/apps/{slug}/architecture")
async def architecture(slug: str) -> dict:
    cwd = workspace.workspace_dir(slug)
    if not cwd.exists():
        raise HTTPException(404, "unknown app")
    return workspace.architecture_payload(cwd)


@router.put("/api/apps/{slug}/architecture")
async def update_architecture(slug: str, body: ArchitectureUpdate) -> dict:
    cwd = workspace.workspace_dir(slug)
    if not cwd.exists():
        raise HTTPException(404, "unknown app")
    try:
        return workspace.save_user_architecture(cwd, body.source, body.graph)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc


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


# --- serve generated React draft and verified builds ------------------------

@router.get("/apps/{slug}/draft/")
async def serve_draft_index(slug: str):
    cwd = workspace.workspace_dir(slug)
    root = workspace.preview_root(cwd)
    entry = workspace.safe_file(root, "index.html")
    if entry is None:
        raise HTTPException(404, "draft preview is not built yet")
    return FileResponse(entry)


@router.get("/apps/{slug}/draft/{path:path}")
async def serve_draft_file(slug: str, path: str):
    root = workspace.preview_root(workspace.workspace_dir(slug))
    target = workspace.safe_file(root, path or "index.html")
    if target is None:
        raise HTTPException(404, "not found")
    return FileResponse(target)

@router.get("/apps/{slug}/")
async def serve_index(slug: str):
    cwd = workspace.workspace_dir(slug)
    built, build_report = await workspace.build_frontend(cwd)
    if not built:
        return HTMLResponse(
            "<h1>React build failed</h1>"
            f"<pre>{html.escape(build_report[:3000])}</pre>",
            status_code=409,
        )
    root = workspace.preview_root(cwd)
    entry = workspace.safe_file(root, "index.html")
    if entry is None:
        raise HTTPException(404, "app not built yet")
    valid, report = await workspace.validate_frontend_contracts(cwd)
    if not valid:
        return HTMLResponse(
            "<h1>Preview blocked</h1>"
            "<p>The generated frontend violates a backend contract. "
            "Ask the agent to fix the reported contract-verifier errors.</p>"
            f"<pre>{html.escape(report[:2000])}</pre>",
            status_code=409,
        )
    live_valid, live_report = workspace.live_verification_status(cwd)
    if not live_valid:
        return HTMLResponse(
            "<h1>Preview is still being checked</h1>"
            "<p>The builder has not released this version because its real backend endpoint tests "
            "have not passed yet.</p>"
            f"<pre>{html.escape(live_report[:2000])}</pre>",
            status_code=409,
        )
    return FileResponse(entry)


@router.get("/apps/{slug}/{path:path}")
async def serve_file(slug: str, path: str):
    cwd = workspace.workspace_dir(slug)
    root = workspace.preview_root(cwd)
    target = workspace.safe_file(root, path or "index.html")
    if target is None:
        # Backward compatibility for old static workspaces and source assets.
        target = workspace.safe_file(cwd, path or "index.html")
    if target is None:
        raise HTTPException(404, "not found")
    return FileResponse(target)
