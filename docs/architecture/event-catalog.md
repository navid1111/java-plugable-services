# Platform Event Catalog

All event types must be registered here before use. Payload schemas will live in `messaging-contracts` and are compatibility-tested in CI.

| Event | Producer/owner | Initial consumers | Ordering | Data class | SLO |
|---|---|---|---|---|---|
| `user.registered.v1` | auth-service | BFF/read projections | per user | internal identity; no credentials | 30 s |
| `user.profile-updated.v1` | auth-service | BFF/read projections | per user version | internal identity | 30 s |
| `user.deactivated.v1` | auth-service | all identity projections | per user version | restricted identity | 10 s |
| `post.created.v1` | tweeter-service | post-search, comment target, media target, BFF projection | per post version | user-generated content | 5 s |
| `post.updated.v1` | tweeter-service | same as created | per post version | user-generated content | 5 s |
| `post.deleted.v1` | tweeter-service | same as created | per post version | identifier/tombstone | 5 s |
| `post.like-count-changed.v1` | tweeter-service | post-search, BFF projection | per post version | public aggregate | 10 s |
| `follow.created.v1` | tweeter-service | feed/notification projections | per user pair | social graph | 30 s |
| `follow.deleted.v1` | tweeter-service | feed/notification projections | per user pair | social graph | 30 s |
| `comment.created.v1` | comment-service | BFF/count projections | per comment | user-generated content | 10 s |
| `comment.deleted.v1` | comment-service | BFF/count projections | per comment | tombstone | 10 s |
| `media.uploaded.v1` | media-service | BFF/media projections | per media version | asset metadata | 30 s |
| `media.processing-completed.v1` | media-service | BFF/media projections | per media version | asset metadata | 30 s |
| `media.processing-failed.v1` | media-service | operations/BFF | per media version | bounded diagnostics | 30 s |
| `media.deleted.v1` | media-service | BFF/media projections | per media version | tombstone | 30 s |
| `booking.created.v1` | booking-service | notifications | per booking version | restricted booking metadata | 10 s |
| `booking.cancelled.v1` | booking-service | notifications | per booking version | restricted booking metadata | 10 s |
| `slot.availability-changed.v1` | booking-service | availability projections | per slot version | public aggregate | 10 s |
| `chat.message-created.v1` | whatsapp-service | push/moderation/analytics | per chat sequence | private content; restricted consumers | 10 s |
| `chat.message-read.v1` | whatsapp-service | notification/analytics | per message/user | private metadata | 30 s |
| `leetcode.submission.judge.requested.v1` | leetcode-service | judge worker | per submission state | source code + hidden tests; restricted | 5 s queue-to-start |
| `leetcode.submission.judge.completed.v1` | judge worker | leetcode-service | per submission state | result + bounded diagnostics | 5 s after execution |

## Registration Requirements

Every new row must name a single producer, schema/version, consumers, required ordering, retention, data classification, freshness SLO, dashboard, and recovery runbook before production use. Passwords, hashes, bearer tokens, private keys, and unnecessary personal data are prohibited in every event.
