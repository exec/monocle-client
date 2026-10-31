# Flight ceilings, approaches and landing geometry

Build height limits block placement, not flight. A stash cruise target above top-row storage must not be silently zeroed by an executor placement-height check. Loaded space, body collision, hazards, borders and the lower void boundary remain enforced.

Final interaction routes use actual body clearance within the bounded approach region; longer cruise retains its buffer. Opening holds and escape states must use the same exception so clearance maintenance does not pull a reachable chest away.

Landing checks actual fractional collision surfaces, full-footprint support and standing clearance at the destination. Transit retains the gliding body and tries nearby support before retreating along breadcrumbs. Preserve specific blockers through workflow handoff. Equipment and lobby-readiness failures are separate from route failures.
