# LeetCode Standalone Integration Demo

This demo mounts the auth service and the leetcode service behind Kong to prove that users can authenticate, browse problems, submit code that runs dynamically in a sandboxed Docker container, and see real-time updates to competition leaderboards.

## Running the demo

1. Build the images from the root of the repo:
   ```bash
   docker compose build auth-service
   docker compose build leetcode-service
   ```

2. Start the demo environment:
   ```bash
   docker compose up -d
   ```

3. Run the smoke test:
   ```bash
   ./smoke.sh
   ```
