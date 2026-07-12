#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICES=(
  "$ROOT/tweeter-service/src/main/java"
  "$ROOT/comment-service/src/main/java"
  "$ROOT/media-service/src/main/java"
  "$ROOT/post-search-service/src/main/java"
  "$ROOT/booking-service/src/main/java"
  "$ROOT/whatsapp-service/src/main/java"
  "$ROOT/leetcode-service/src/main/java"
)

# Username is permitted as a display snapshot. Repository identity, membership,
# ownership, idempotency and authorization must use immutable user IDs.
forbidden='findBy[A-Za-z0-9_]*Username|existsBy[A-Za-z0-9_]*Username|deleteBy[A-Za-z0-9_]*Username|legacyToken|legacy:[A-Za-z]'
hits="$(grep -rInE "$forbidden" "${SERVICES[@]}" 2>/dev/null || true)"
if [[ -n "$hits" ]]; then
  echo "Username relational identity or legacy identity fallback found:" >&2
  echo "$hits" >&2
  exit 1
fi

required=(
  'tweeter-service/src/main/java/com/example/tweeter/model/Post.java:authorUserId'
  'comment-service/src/main/java/com/example/comment/model/Comment.java:authorUserId'
  'media-service/src/main/java/com/example/media/model/MediaAsset.java:uploaderUserId'
  'booking-service/src/main/java/com/example/booking/model/Booking.java:userId'
  'whatsapp-service/src/main/java/com/example/whatsapp/model/ChatParticipant.java:userId'
  'whatsapp-service/src/main/java/com/example/whatsapp/model/InboxEntry.java:recipientUserId'
  'whatsapp-service/src/main/java/com/example/whatsapp/model/Message.java:senderUserId'
  'leetcode-service/src/main/java/com/example/leetcode/model/Submission.java:userId'
)
for entry in "${required[@]}"; do
  file="${entry%%:*}"
  field="${entry#*:}"
  grep -Fq "$field" "$ROOT/$file" || {
    echo "Stable identity field '$field' is missing from $file" >&2
    exit 1
  }
done

echo "Stable user identity verification passed."
