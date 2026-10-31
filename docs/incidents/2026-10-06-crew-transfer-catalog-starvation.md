# Crew transfer catalog starvation

Refreshing and summarizing a full stash catalog on every worker checkpoint can starve coordinated travel and TPA dispatch even while workers remain connected.

Both host adapters rate-limit periodic catalog publication per connection. Reconnects and explicit definition, edit and import updates still synchronize promptly. Heartbeat socket tests verify no repeated catalog send, while edit tests verify immediate updates.

Healthy control latency after this change is not proof that an entire transfer or every subsequent travel barrier will complete.
