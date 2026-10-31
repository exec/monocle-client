# Top-chest flight and walking handoff

Adjacency goals are not equivalent to opening reach or line of sight. Repeated cleared goals and jump attempts can look like progress while a non-building walking lease cannot reach a top chest.

Prefer flight beyond interaction distance or for elevated destinations, bound failed takeoff/route/clearance attempts per target and then use the existing safe fallback. Opportunistically reachable containers are opened before navigation.

Diagnostics expose movement type, destination, forced jump, request count and goal/controller state. Tests cover shared callers and bounded retries; a historical jumping loop without executor evidence does not prove a specific controller caused it.
