# Crew pickup confirmation

An intermediate contents packet may show the source changed before the carried gain is confirmed. That packet alone must neither confirm pickup nor immediately discard a pending transfer. Local prediction also requires reconciliation before issuing a move.

Pickup uses a reconciled baseline and the existing forced two-click operation. Confirmation requires the exact source/destination/cursor evidence; intermediate negatives wait within the bounded deadline. Pending clicks are never blindly replayed, and confirmed receipts cannot be undone by late packets.

Recovery must inspect actual carried cargo and deposit confirmed loads before replacement collection. Receipt totals are not proof of current inventory. Tests cover packet ordering, component/count mismatches, duplicate confirmation and recovery without new pickup.
