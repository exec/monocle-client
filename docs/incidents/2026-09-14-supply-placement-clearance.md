# Supply placement body clearance

Adjacent feet can still leave the player's hitbox overlapping a planned container. Restocking checks the actual body and moves to a fixed supported clearance point before retrying placement.

The clearance margin accounts for walking arrival tolerance. Container ownership, tracked drops, paired-chest facing and host coordination remain unchanged. Tests exercise all headings, negative synthetic geometry and repeated placement spots.
