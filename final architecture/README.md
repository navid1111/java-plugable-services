# Final Architecture

Architecture diagrams for the Pluggable Services platform. Each file has a Mermaid diagram
plus the domain model, contracts, and notable design choices for that service.

| # | File | Prefix | What it is |
|---|------|--------|-----------|
| 00 | [System Overview](00-system-overview.md) | — | Whole-system topology: Kong edge, all services, events, DBs |
| 01 | [auth-service](01-auth-service.md) | `/auth` | User identity + JWT minting (public routes) |
| 02 | [tweeter-service](02-tweeter-service.md) | `/posts` | Posts, follows, feed; export origin; outbox |
| 03 | [comment-service](03-comment-service.md) | `/comments` | Reusable comments on any target reference |
| 04 | [post-search-service](04-post-search-service.md) | `/post-search` | Keyword search projection (manual inverted index) |
| 05 | [media-service](05-media-service.md) | `/media` | Cloudinary attachments; upload-intent + deletion jobs |
| 06 | [booking-service](06-booking-service.md) | `/bookings` | Generic slot booking; no-double-booking |
| 07 | [whatsapp-service](07-whatsapp-service.md) | `/chat` | Chat: REST history + WebSocket delivery + inbox |
| 08 | [leetcode-service](08-leetcode-service.md) | `/leetcode` | Problems + sandboxed judge worker (2 roles) |
| 09 | [bff](09-bff.md) | `/bff` | Read composer; critical vs optional, deadlines |
| 10 | [platform](10-platform-messaging-observability.md) | — | RabbitMQ backbone, contracts, outbox, observability |

## Cross-cutting patterns (recurring across services)

- **Kong edge auth** — JWT verified at the gateway; services trust the verified token.
- **Database-per-service** — no shared tables; data crosses boundaries via events or workload-JWT HTTP export.
- **Transactional outbox** — domain write + event row in one transaction, scheduled relay to RabbitMQ (at-least-once).
- **Workload JWT** — separate identity plane for internal `/internal/*` service-to-service calls.
- **Denormalized identity** — services store `userId` (UUID) + username so reads never call auth synchronously; `IdentityBackfill` reconciles gaps.
- **Plug-kit packaging** — each service ships `plug/` (compose fragment, `kong-setup.sh`, `smoke.sh`) so it can be plugged into the gateway independently.

> Diagrams use Mermaid. View in any Mermaid-aware Markdown renderer (GitHub, VS Code with a Mermaid extension, Obsidian, etc.).
