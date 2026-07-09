"""App-builder agent backend: Hermes SDK generation over pluggable Java backends."""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .routes import router

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="app-builder agent backend")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(router)


@app.on_event("startup")
async def _startup() -> None:
    settings.workspaces_dir.mkdir(parents=True, exist_ok=True)


def main() -> None:
    import uvicorn

    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    main()
